package app.spammy.hof.account.service

/**
 * HOF 비밀번호 저장 방식을 추상화한 인터페이스다.
 *
 * 계정 서비스는 이 인터페이스만 사용하므로 구체적인 암호화 형식과 키 관리를 알 필요가 없다.
 */
interface CredentialCipher {
    /**
     * 사용자가 입력한 원본 비밀번호를 저장용 문자열로 변환한다.
     */
    fun encrypt(rawPassword: String): String

    /**
     * 저장된 비밀번호 문자열을 HOF 로그인 요청에 사용할 원본 값으로 복원한다.
     */
    fun decrypt(encryptedPassword: String): String
}
