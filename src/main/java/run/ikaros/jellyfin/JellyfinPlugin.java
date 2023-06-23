package run.ikaros.jellyfin;

import org.pf4j.PluginWrapper;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import run.ikaros.api.plugin.BasePlugin;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class JellyfinPlugin extends BasePlugin implements ApplicationContextAware {

    private ApplicationContext applicationContext;
    private Disposable mediaDirDisposable;

    public JellyfinPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }


    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public void start() {
        MediaDirInit mediaDirInit = applicationContext.getBean(MediaDirInit.class);
        mediaDirDisposable = mediaDirInit.generate();
        log.info("start generate jellyfin media subject dirs of 15 minutes ...");
    }

    @Override
    public void stop() {
        if(mediaDirDisposable != null) {
            mediaDirDisposable.dispose();
            log.info("stop generate jellyfin media subject dirs of 15 minutes ...");
        }
    }
}
