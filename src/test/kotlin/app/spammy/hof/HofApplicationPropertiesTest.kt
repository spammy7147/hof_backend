package app.spammy.hof

import org.junit.jupiter.api.Test
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HofApplicationPropertiesTest {
    @Test
    fun defaultConfigurationDefinesPersistentFileH2Datasource() {
        val properties = Properties()
        val resource = requireNotNull(
            Thread.currentThread().contextClassLoader.getResourceAsStream("application.properties"),
        )
        resource.use(properties::load)

        assertEquals("hof", properties.getProperty("spring.application.name"))
        assertTrue(
            properties.getProperty("spring.datasource.url").startsWith("\${SPRING_DATASOURCE_URL:jdbc:h2:file:./data/hof;"),
        )
        assertTrue(properties.getProperty("spring.datasource.url").contains("DB_CLOSE_DELAY=-1"))
        assertEquals(
            "\${SPRING_DATASOURCE_DRIVER_CLASS_NAME:org.h2.Driver}",
            properties.getProperty("spring.datasource.driver-class-name"),
        )
        assertTrue(properties.containsKey("spring.datasource.username"))
        assertTrue(properties.containsKey("spring.datasource.password"))
    }
}
