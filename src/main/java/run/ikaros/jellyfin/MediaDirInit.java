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
    private final String workDirAbsolutePath;

    public MediaDirInit(SubjectOperate subjectOperate, FileOperate fileOperate,
                        IkarosProperties ikarosProperties) {
        this.subjectOperate = subjectOperate;
        this.fileOperate = fileOperate;
        this.ikarosProperties = ikarosProperties;
        workDirAbsolutePath = ikarosProperties.getWorkDir().toFile().getAbsolutePath();
    }


    // @EventListener(ApplicationReadyEvent.class)
    public Disposable generate() {
        return Flux.interval(Duration.ofMinutes(15))
            .doOnEach(tick -> generateJellyfinMediaDirAndFiles())
            .subscribe();
    }

    private void generateJellyfinMediaDirAndFiles() {
        String mediaDirAbsolutePath = workDirAbsolutePath + File.separatorChar + MEDIA_DIR_NAME;
        PagingWrap<Subject> pagingWrap = new PagingWrap<>(1, 9999, 0, null);

        File mediaDir = new File(mediaDirAbsolutePath);
        if (!mediaDir.exists()) {
            mediaDir.mkdirs();
            log.debug("create media dir in path: [{}].", mediaDirAbsolutePath);
        }

        subjectOperate.findAllByPageable(pagingWrap)
            .doOnEach(subjectSignal -> handleSubject(subjectSignal, mediaDirAbsolutePath))
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe();
    }

    private void handleSubject(Signal<Subject> subjectSignal, String mediaDirAbsolutePath) {
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
        String subjectDirAbsolutePath = mediaDirAbsolutePath +
            File.separatorChar + buildMediaAnimeDirName(subject);
        File subjectDirFile = new File(subjectDirAbsolutePath);
        if (!subjectDirFile.exists()) {
            subjectDirFile.mkdirs();
            log.debug("create subject dir in path: [{}].", subjectDirAbsolutePath);
        }

        // generate tvshow.nfo file.
        File tvShowFile = new File(subjectDirAbsolutePath
            + File.separatorChar + "tvshow.nfo");
        if (tvShowFile.exists()) {
            tvShowFile.delete();
            log.debug("delete subject:[{}] tv show file:[{}].", subject.getName(),
                tvShowFile.getAbsolutePath());
        }
        try {
            XmlUtils.generateJellyfinTvShowNfoXml(tvShowFile.getAbsolutePath(),
                subject.getSummary(), subject.getNameCn(),
                subject.getName(),
                bgmTvIdOp.orElse(""));
            log.debug("create subject:[{}] tv show file:[{}].", subject.getName(),
                tvShowFile.getAbsolutePath());
        } catch (Exception e) {
            log.warn("create tv show file fail, skip current subject:[{}]. ",
                subject.getName(), e);
            return;
        }

        // generate cover img file.
        String coverAbsolutePath = workDirAbsolutePath +
            (subject.getCover().startsWith("/") ? subject.getCover() : "/" + subject.getCover());
        File coverFile = new File(coverAbsolutePath);
        if (coverFile.exists()) {
            String postfix = FileUtils.parseFilePostfix(coverAbsolutePath);
            String posterFilePath = subjectDirAbsolutePath
                + File.separatorChar
                + "poster"
                + (StringUtils.hasText(postfix)
                ? (postfix.startsWith(".") ? postfix : "." + postfix)
                : ".jpg");
            File posterFile = new File(posterFilePath);
            if (!posterFile.exists()) {
                try {
                    if (!posterFile.exists()) {
                        Files.createLink(posterFile.toPath(), coverFile.toPath());
                        log.debug(
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
                linkEpisodeFileAndGenerateNfo(bgmTvIdOp, subjectDirAbsolutePath,
                    workDirAbsolutePath, episode, fileEntity));

        }
    }

    private static void linkEpisodeFileAndGenerateNfo(Optional<String> bgmTvIdOp,
                                                      String subjectDirAbsolutePath,
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
            File targetEpisodeFile =
                new File(subjectDirAbsolutePath + File.separatorChar + originalFileName);
            try {
                if (!targetEpisodeFile.exists()) {
                    Files.createLink(targetEpisodeFile.toPath(), episodeFile.toPath());
                    log.debug(
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
                new File(subjectDirAbsolutePath + File.separatorChar
                    + originalFileName.replaceAll(RegexConst.FILE_POSTFIX, "") + ".nfo");
            if (episodeNfoFile.exists()) {
                episodeNfoFile.delete();
                log.debug("delete episode nfo file, episode:[{}], nfo file path:[{}].",
                    episode.getName(), episodeNfoFile.getAbsolutePath());
            }
            XmlUtils.generateJellyfinEpisodeNfoXml(episodeNfoFile.getAbsolutePath(),
                episode.getDescription(),
                StringUtils.hasText(episode.getNameCn()) ? episode.getNameCn() :
                    episode.getName(),
                "1",
                String.valueOf(episode.getSequence()), bgmTvIdOp.orElse(""));
            log.debug("create episode nfo file, episode:[{}], nfo file path:[{}].",
                episode.getName(), episodeNfoFile.getAbsolutePath());
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

        StringBuilder sb = new StringBuilder();
        sb.append(nameCn)
            .append(StringUtils.hasText(nameCn) ? " - " : "")
            .append(name)
            .append(" (")
            .append(date)
            .append(")");
        String fileEncode = System.getProperty("file.encoding");
        log.debug("current system fileEncode: {}", fileEncode);

        return sb.toString()
            .replace(":", "")
            .replace("/", "")
            .replace("\\", "")
            .replace("*", "")
            .replace("?", "")
            .replace("\"", "")
            .replace("<", "")
            .replace(">", "")
            .replace(">", "")
            .replace("|", "");
    }

}
