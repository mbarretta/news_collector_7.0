import ch.qos.logback.classic.encoder.PatternLayoutEncoder

appender("FILE", RollingFileAppender) {
    file = "log.log"
    rollingPolicy(TimeBasedRollingPolicy) {
        fileNamePattern = "log.log.%d{yyyy-MM-dd}"
        maxHistory = 10
        totalSizeCap = "1GB"
    }
    encoder(PatternLayoutEncoder) {
        pattern = "%date{MM.dd.yyyy HH:mm:ss.SSS} [%thread] %-5level %logger{35} - %msg%n"
    }
}
appender("STDOUT", ConsoleAppender) {
    encoder(PatternLayoutEncoder) {
        pattern = "%date{MM.dd.yyyy HH:mm:ss.SSS} [%thread] %-5level %logger{35} - %msg%n"
    }
}
root(TRACE, ["FILE", "STDOUT"])
