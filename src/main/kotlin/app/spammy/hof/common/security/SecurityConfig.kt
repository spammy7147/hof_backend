package app.spammy.hof.common.security

import app.spammy.hof.auth.config.AuthProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
import org.springframework.http.HttpMethod
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
@EnableWebSecurity
/**
 * 로컬 앱/API 통신을 위한 Spring Security 설정이다.
 */
class SecurityConfig(
    private val properties: AuthProperties,
    private val authenticationEntryPoint: ApiAuthenticationEntryPoint,
) {
    /**
     * 인증 API와 health check만 공개하고 나머지 요청은 유효한 Bearer JWT를 요구한다.
     */
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .sessionManagement { sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .cors { }
            .csrf { csrf -> csrf.disable() }
            .authorizeHttpRequests { requests ->
                requests
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/auth/login",
                        "/api/auth/refresh",
                        "/api/auth/logout",
                        "/internal/app-releases/android",
                    ).permitAll()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/app-releases/android/latest",
                        "/api/app-releases/android/*/download",
                        "/extension/lastest",
                    ).permitAll()
                    .requestMatchers(HttpMethod.HEAD, "/extension/lastest").permitAll()
                    .requestMatchers("/actuator/health", "/error").permitAll()
                    .anyRequest().authenticated()
            }
            .oauth2ResourceServer { resourceServer ->
                resourceServer.jwt { }
                resourceServer.authenticationEntryPoint(authenticationEntryPoint)
            }
            .exceptionHandling { exceptions -> exceptions.authenticationEntryPoint(authenticationEntryPoint) }
            .httpBasic { basic -> basic.disable() }
            .formLogin { form -> form.disable() }
            .logout { logout -> logout.disable() }
            .addFilterBefore(HttpsEnforcementFilter(properties), BearerTokenAuthenticationFilter::class.java)

        return http.build()
    }

    /**
     * Expo web/native 개발 환경에서 백엔드 API를 호출할 수 있게 CORS를 허용한다.
     */
    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration = CorsConfiguration()
        configuration.allowedOrigins = properties.allowedOrigins
        configuration.allowedOriginPatterns = properties.allowedOriginPatterns
        configuration.allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        configuration.allowedHeaders = listOf("*")
        configuration.exposedHeaders = listOf("Content-Type", "X-HOF-Observed-Status")
        configuration.allowCredentials = true

        return UrlBasedCorsConfigurationSource().also { source ->
            source.registerCorsConfiguration("/api/**", configuration)
        }
    }
}
