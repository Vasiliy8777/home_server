package ru.homeserver.photoshare.homeserver.config;

import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import ru.homeserver.photoshare.homeserver.service.FileService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

/*
 * Конфигурация Spring MVC.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final FileService fileService;

    public WebConfig(FileService fileService) {
        this.fileService = fileService;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        /*
         * toUri().toString() превращает путь к папке в URI,
         * который понимает Spring ResourceHandler.
         *
         * Например:
         * file:///D:/MediaLibrary/
         */
        String location = fileService.getRootPath().toUri().toString();

        /*
         * addResourceHandler("/media/**")
         * говорит:
         * "Когда браузер запрашивает URL вида /media/что-то,
         *  ищи это в указанной папке на диске".
         *
         * Это механизм статической раздачи файлов.
         */
        registry.addResourceHandler("/media/**")
                .addResourceLocations(location);
    }
    @Configuration
    public class AsyncConfig implements WebMvcConfigurer {

        @Override
        public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(4);
            executor.setMaxPoolSize(8);
            executor.setQueueCapacity(20);
            executor.setThreadNamePrefix("stream-");
            executor.initialize();

            configurer.setTaskExecutor(executor);
            configurer.setDefaultTimeout(60_000);
        }
    }
}