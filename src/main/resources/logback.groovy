import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import co.elastic.logging.logback.EcsEncoder

appender("FILE", RollingFileAppender) {
    file = "log.log"
    rollingPolicy(TimeBasedRollingPolicy) {
        fileNamePattern = "log.log.%d{yyyy-MM-dd}"
        maxHistory = 5
        totalSizeCap = "1GB"
    }
    encoder(EcsEncoder) {
       serviceName = "news_demo"
    }
}
appender("STDOUT", ConsoleAppender) {
    encoder(PatternLayoutEncoder) {
        pattern = "%date{MM.dd.yyyy HH:mm:ss.SSS} [%thread] %-5level %logger{35} - %msg%n"
    }
}
root(INFO, ["FILE", "STDOUT"])
