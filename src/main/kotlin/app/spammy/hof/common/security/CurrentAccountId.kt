package app.spammy.hof.common.security

import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import org.springframework.context.annotation.Configuration
import org.springframework.core.MethodParameter
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/** 컨트롤러 인자의 계정 ID가 요청 URL이나 body가 아니라 인증된 JWT subject에서 와야 함을 표시한다. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class CurrentAccountId

/** 검증을 마친 Spring Security 인증 객체에서 JWT subject를 읽어 양수 계정 ID로 변환한다. */
class CurrentAccountIdArgumentResolver : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.hasParameterAnnotation(CurrentAccountId::class.java) &&
            (parameter.parameterType == Long::class.java || parameter.parameterType == Long::class.javaPrimitiveType)

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Any {
        val principal = SecurityContextHolder.getContext().authentication?.principal
        val subject = (principal as? Jwt)?.subject
        return subject?.toLongOrNull()?.takeIf { it > 0 }
            ?: throw ApiException(ErrorCode.AUTH_TOKEN_INVALID, "로그인 정보가 올바르지 않습니다.")
    }
}

/** Spring MVC가 모든 컨트롤러에서 [CurrentAccountId] 인자를 해석하도록 resolver를 등록한다. */
@Configuration
class CurrentAccountWebMvcConfig : WebMvcConfigurer {
    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers += CurrentAccountIdArgumentResolver()
    }
}
