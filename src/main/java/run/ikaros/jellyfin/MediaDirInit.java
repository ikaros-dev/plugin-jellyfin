package run.ikaros.jellyfin;

import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Signal;
import reactor.core.scheduler.Schedulers;
import run.ikaros.api.core.file.FileOperate;
import run.ikaros.api.core.subject.*;
import run.ikaros.api.infra.properties.IkarosProperties;
import run.ikaros.api.infra.utils.FileUtils;
import run.ikaros.api.store.entity.FileEntity;
import run.ikaros.api.store.enums.SubjectSyncPlatform;
import run.ikaros.api.wrap.PagingWrap;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class MediaDirInit {
    private static final String MEDIA_DIR_NAME = "jellyfin";
    private final SubjectOperate subjectOperate;
    private final FileOperate fileOperate;
    private final IkarosProperties ikarosProperties;

    public MediaDirInit(SubjectOperate subjectOperate, FileOperate fileOperate,
                        IkarosProperties ikarosProperties) {
        this.subjectOperate = subjectOperate;
        this.fileOperate = fileOperate;
        this.ikarosProperties = ikarosProperties;
    }


    // @EventListener(ApplicationReadyEvent.class)
    public Disposable generate() {
        return Flux.interval(Duration.ofSeconds(30))
            .doOnEach(tick -> generateJellyfinMediaDirAndFiles())
            .subscribe();
    }

    private void generateJellyfinMediaDirAndFiles() {
        Path workDir = ikarosProperties.getWorkDir();
        Path mediaDirPath = workDir.resolve(MEDIA_DIR_NAME);
        PagingWrap<Subject> pagingWrap = new PagingWrap<>(1, 9999, 0, null);

        subjectOperate.findAllByPageable(pagingWrap)
            .doOnEach(subjectSignal -> handleSubject(subjectSignal, mediaDirPath))
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe();
    }

    private void handleSubject(Signal<Subject> subjectSignal, Path mediaDirPath) {
        Subject subject = subjectSignal.get();
        if (subject == null) {
            return;
        }

        if (subject.getEpisodes() == null || subject.getEpisodes().isEmpty()) {
            return;
        }

        List<SubjectSync> syncs = subject.getSyncs();
        Optional<String> bgmTvIdOp = syncs.stream().filter(
                subjectSync ->
                    SubjectSyncPlatform.BGM_TV.equals(subjectSync.getPlatform()))
            .map(SubjectSync::getPlatformId)
            .findFirst();

        // generate subject dir.
        Path subjectDirPath = mediaDirPath.resolve(buildMediaAnimeDirName(subject));
        File subjectDirFile = subjectDirPath.toFile();
        if (!subjectDirFile.exists()) {
            subjectDirFile.mkdirs();
        }

        // generate tvshow.nfo file.
        File tvShowFile = subjectDirPath.resolve("tvshow.nfo").toFile();
        if (!tvShowFile.exists()) {
            try {
                XmlUtils.generateJellyfinTvShowNfoXml(tvShowFile.getAbsolutePath(),
                    subject.getSummary(), subject.getNameCn(),
                    subject.getName(),
                    bgmTvIdOp.orElse(""));
            } catch (Exception e) {
                log.warn("create tv show file fail, skip current subject:[{}]. ",
                    subject.getName(), e);
                return;
            }
        }

        // generate cover img file.
        Path workDir = ikarosProperties.getWorkDir();
        String workDirAbsolutePath = workDir.toFile().getAbsolutePath();
        String coverAbsolutePath = workDirAbsolutePath +
            (subject.getCover().startsWith("/") ? subject.getCover() : "/" + subject.getCover());
        File coverFile = new File(coverAbsolutePath);
        if (coverFile.exists()) {
            String postfix = FileUtils.parseFilePostfix(coverAbsolutePath);
            String posterFilePath = subjectDirPath.toFile().getAbsolutePath()
                + File.separator
                + "poster"
                + (StringUtils.hasText(postfix)
                ? (postfix.startsWith(".") ? postfix : "." + postfix)
                : ".jpg");
            File posterFile = new File(posterFilePath);
            if (!posterFile.exists()) {
                try {
                    if (!posterFile.exists()) {
                        Files.createLink(posterFile.toPath(), coverFile.toPath());
                        log.warn(
                            "create jellyfin poster.jpg hard link success, link={}, existing={}",
                            posterFilePath, coverAbsolutePath);
                    }
                } catch (IOException e) {
                    log.warn(
                        "create jellyfin poster.jpg hard link fail, link={}, existing={}",
                        posterFilePath, coverAbsolutePath);
                }
            }
        }

        // generate episode file and nfo
        List<Episode> episodes = subject.getEpisodes();
        for (Episode episode : episodes) {
            if (episode.getResources() == null || episode.getResources().isEmpty()) {
                continue;
            }
            EpisodeResource episodeResource = episode.getResources().get(0);
            Long fileId = episodeResource.getFileId();
            fileOperate.findById(fileId).subscribe(fileEntity ->
                linkEpisodeFileAndGenerateNfo(bgmTvIdOp, subjectDirPath,
                    workDirAbsolutePath, episode, fileEntity));

        }
    }

    private static void linkEpisodeFileAndGenerateNfo(Optional<String> bgmTvIdOp,
                                                      Path subjectDirPath,
                                                      String workDirAbsolutePath,
                                                      Episode episode,
                                                      FileEntity fileEntity) {
        if (fileEntity == null) {
            log.warn("skip operate, file entity is null for episode: [{}].",
                episode.getName());
            return;
        }
        String originalFileName = fileEntity.getOriginalName();
        String epUrl = fileEntity.getUrl();

        String epFileAbsolutePath =
            workDirAbsolutePath + (epUrl.startsWith("/") ? epUrl : "/" + epUrl);
        File episodeFile = new File(epFileAbsolutePath);
        if (episodeFile.exists()) {
            // link episode file
            File targetEpisodeFile = subjectDirPath.resolve(originalFileName).toFile();
            try {
                if (!targetEpisodeFile.exists()) {
                    Files.createLink(targetEpisodeFile.toPath(), episodeFile.toPath());
                    log.warn(
                        "create jellyfin episode hard link success, link={}, existing={}",
                        targetEpisodeFile.getAbsolutePath(), epFileAbsolutePath);
                }
            } catch (IOException e) {
                log.warn(
                    "create jellyfin episode hard link fail, link={}, existing={}",
                    targetEpisodeFile.getAbsolutePath(), epFileAbsolutePath, e);
            }
            // generate nfo file
            File episodeNfoFile =
                subjectDirPath.resolve(
                        originalFileName.replaceAll(RegexConst.FILE_POSTFIX, "") + ".nfo")
                    .toFile();
            if (!episodeNfoFile.exists()) {
                XmlUtils.generateJellyfinEpisodeNfoXml(episodeNfoFile.getAbsolutePath(),
                    episode.getDescription(),
                    StringUtils.hasText(episode.getNameCn()) ? episode.getNameCn() :
                        episode.getName(),
                    "1",
                    String.valueOf(episode.getSequence()), bgmTvIdOp.orElse(""));
            }
        }
    }

    /**
     * 条目 中文名 - 英文名 (年月日) 这种格式生成媒体番剧目录，
     * 比如 孤独摇滚！- ぼっち・ざ・ろっく！(2022-10-08)
     * .
     *
     * @param subject 条目ID
     * @return 媒体目录名称
     */
    private String buildMediaAnimeDirName(Subject subject) {
        Assert.notNull(subject, "'subject' must not null.");
        String nameCn = subject.getNameCn();
        String name = subject.getName();
        LocalDateTime airTime = subject.getAirTime();
        String date = "";
        if (airTime != null) {
            date = airTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        }
        return StringUtils.hasText(nameCn) ? nameCn : " - "
            + name +
            " (" +
            date +
            ")";
    }

}
