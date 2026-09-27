FROM public.ecr.aws/amazoncorretto/amazoncorretto:21 AS build
WORKDIR /workspace
RUN dnf install -y unzip && dnf clean all
COPY backend/mvnw backend/mvnw
COPY backend/.mvn backend/.mvn
COPY demo-services demo-services
ARG SERVICE
RUN sh backend/mvnw -f demo-services/pom.xml -pl :${SERVICE} -am --batch-mode -DskipTests package

FROM public.ecr.aws/amazoncorretto/amazoncorretto:21
WORKDIR /app
ARG SERVICE
COPY --from=build /workspace/demo-services/${SERVICE}/target/${SERVICE}-0.0.1-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/app.jar"]
