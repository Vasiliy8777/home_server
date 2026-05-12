package ru.homeserver.photoshare.homeserver.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import ru.homeserver.photoshare.homeserver.security.LoginFailureHandler;
import ru.homeserver.photoshare.homeserver.security.LoginSuccessHandler;

@Configuration
public class SecurityConfig {
    private final LoginFailureHandler loginFailureHandler;
    private final LoginSuccessHandler loginSuccessHandler;

    public SecurityConfig(
            LoginFailureHandler loginFailureHandler,
            LoginSuccessHandler loginSuccessHandler
    ) {
        this.loginFailureHandler = loginFailureHandler;
        this.loginSuccessHandler = loginSuccessHandler;
    }
    @Value("${app.security.username}")
    private String username;

    @Value("${app.security.password}")
    private String password;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        CsrfTokenRequestAttributeHandler requestHandler =
                new CsrfTokenRequestAttributeHandler();

        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(requestHandler)
                        .ignoringRequestMatchers("/api/files/prepare-folder",
                                "/api/files/metadata/card-bulk")
                )/*.headers(headers -> headers
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31536000)
                        )
                )*/
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/error").permitAll()
                        .anyRequest().authenticated()
                )
                .formLogin(login -> login
                        .successHandler(loginSuccessHandler)
                        .failureHandler(loginFailureHandler)
                        .permitAll()
                )
                /*.formLogin(Customizer.withDefaults())*/
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID", "XSRF-TOKEN")
                )
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(sessionFixation -> sessionFixation.migrateSession())
                        .maximumSessions(1)
                );

        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder passwordEncoder) {
        UserDetails user = User.withUsername(username)
                .password(passwordEncoder.encode(password))
                .roles("USER")
                .build();

        return new InMemoryUserDetailsManager(user);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
/*package ru.homeserver.photoshare.homeserver.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

*//*
 * Класс конфигурации безопасности.
 *
 * @Configuration говорит Spring:
 * "Этот класс содержит настройки и бины".
 *//*
@Configuration
public class SecurityConfig {

    *//*
     * @Value читает значение из application.yml
     *
     * Здесь мы берем:
     * app.security.username
     *//*
    @Value("${app.security.username}")
    private String username;

    *//*
     * Здесь читаем пароль из application.yml
     *//*
    @Value("${app.security.password}")
    private String password;

    *//*
     * Этот бин задает правила безопасности.
     *
     * SecurityFilterChain — это цепочка security-фильтров,
     * через которые проходит каждый HTTP-запрос.
     *
     * Упрощенно:
     * браузер -> security filters -> controller/resource
     *//*
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                *//*
                 * CSRF — защита от межсайтовой подделки запросов.
                 *
                 * По умолчанию Spring Security требует CSRF token
                 * для POST/PUT/DELETE и др.
                 *
                 * Мы отключаем CSRF, чтобы не усложнять JS-код,
                 * потому что у нас домашний проект и запросы идут через fetch.
                 *
                 * Для интернет-публичного приложения лучше CSRF не отключать.
                 *//*
                .csrf(csrf -> csrf.disable())

                *//*
                 * authorizeHttpRequests — правила доступа к URL.
                 *//*
                .authorizeHttpRequests(auth -> auth
                        *//*
                         * /error можно открывать без авторизации,
                         * чтобы при ошибке пользователь не попадал
                         * в странное зацикливание.
                         *//*
                        .requestMatchers("/error").permitAll()

                        *//*
                         * Любой другой URL требует аутентификации.
                         *//*
                        .anyRequest().authenticated()
                )

                *//*
                 * formLogin(Customizer.withDefaults())
                 *
                 * Говорит Spring Security включить стандартную HTML-форму логина.
                 * Если пользователь не авторизован и открывает защищенный URL,
                 * его перенаправят на /login.
                 *//*
                .formLogin(Customizer.withDefaults())

                *//*
                 * Настройка logout.
                 * После выхода пользователь попадет на /login?logout
                 *//*
                .logout(logout -> logout.logoutSuccessUrl("/login?logout"));

        return http.build();
    }

    *//*
     * UserDetailsService — источник пользователей.
     *
     * Здесь мы создаем пользователя "в памяти".
     * То есть не из базы данных, не из файла, а прямо внутри приложения.
     *
     * Для домашнего проекта это самый простой вариант.
     *//*
    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder passwordEncoder) {

        *//*
         * User.withUsername(...) — builder стандартного Spring Security user.
         *
         * Важно:
         * password() здесь ожидает уже "подготовленный" пароль.
         * Поэтому мы пропускаем его через passwordEncoder.encode(password)
         *
         * То есть пароль хранится в системе не как plain text,
         * а в виде bcrypt-хэша.
         *//*
        UserDetails user = User.withUsername(username)
                .password(passwordEncoder.encode(password))
                .roles("USER")
                .build();

        *//*
         * InMemoryUserDetailsManager — реализация UserDetailsService,
         * которая хранит пользователей прямо в памяти JVM.
         *//*
        return new InMemoryUserDetailsManager(user);
    }

    *//*
     * PasswordEncoder — стратегия кодирования пароля.
     *
     * BCryptPasswordEncoder:
     * - создает хэш пароля
     * - добавляет соль
     * - делает проверку более безопасной
     *
     * Когда пользователь логинится, Spring Security не сравнивает пароли
     * как обычные строки, а использует encoder.matches(raw, encoded).
     *//*
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}*/
