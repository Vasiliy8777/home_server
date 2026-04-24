package ru.homeserver.photoshare.homeserver;


import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/*
 * Этот класс — точка входа в приложение.
 *
 * @SpringBootApplication — это составная аннотация.
 * Она объединяет в себе:
 *
 * 1. @Configuration
 *    Говорит Spring, что этот класс может содержать настройки и бины.
 *
 * 2. @EnableAutoConfiguration
 *    Говорит Spring Boot автоматически настраивать приложение
 *    на основе подключенных зависимостей.
 *
 *    Например:
 *    - если есть spring-boot-starter-web, поднимется web-контекст
 *    - если есть spring-boot-starter-security, поднимется security-конфиг
 *
 * 3. @ComponentScan
 *    Говорит Spring сканировать текущий пакет и подпакеты
 *    в поиске компонентов:
 *    - @Service
 *    - @Controller
 *    - @Configuration
 *    - @Repository
 */
@SpringBootApplication
public class MediaServerApplication {

    public static void main(String[] args) {

        SpringApplication.run(MediaServerApplication.class, args);
    }
}