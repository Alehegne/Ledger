FROM eclipse-temurin:25-jdk
ADD target/Ledger_Final.jar /tmp/Ledger_Final.jar
ENTRYPOINT ["java", "-jar", "/tmp/Ledger_Final.jar"]
