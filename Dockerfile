# One image for every VA-BAGS service (Design/11 §2).
#
# Build the jars ON THE HOST first (uses your working ~/.m2 + repo access — the build container
# has neither, hence we don't run Maven inside it):
#   mvn -DskipTests clean package
# Then build an image per service:
#   docker build --build-arg MODULE=order-service -t vabags/order-service:dev .
#
# 2 stages: (1) crack the Spring Boot layered jar open, (2) copy the layers into a tiny non-root
# JRE runtime. The layer split lets Docker cache dependencies separately from your app code.

# ---------- stage 1: extract layers from the pre-built jar ----------
FROM eclipse-temurin:17-jre AS layers
WORKDIR /app
ARG MODULE
COPY ${MODULE}/target/*.jar app.jar 
# the repackaged boot jar (target/*.jar; .jar.original is skipped)
#RUN java -Djarmode=layertools -jar app.jar extract
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# ---------- stage 2: runtime ----------
FROM eclipse-temurin:17-jre
WORKDIR /app
#RUN useradd -r -u 1001 spring
RUN useradd -r spring                   
# never run as root
COPY --from=layers /app/extracted/dependencies/          ./
COPY --from=layers /app/extracted/spring-boot-loader/     ./
COPY --from=layers /app/extracted/snapshot-dependencies/  ./
COPY --from=layers /app/extracted/application/            ./
#USER 1001
USER spring

# JarLauncher = the Spring Boot 3.x layered-jar entry point; JAVA_TOOL_OPTIONS (set in k8s) is honoured automatically
ENTRYPOINT ["java","org.springframework.boot.loader.launch.JarLauncher"]
