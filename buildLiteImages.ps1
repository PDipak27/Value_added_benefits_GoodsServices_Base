# Build container images for the 4 Lite services (jars must be built first — see below).
#   mvn -pl api-gateway,lite-order-service,lite-inventory-service,lite-billing-service -am -DskipTests package
#   .\buildLiteImages.ps1
$services = @("api-gateway", "lite-order-service", "lite-inventory-service", "lite-billing-service")
foreach ($s in $services) {
    docker build -f Dockerfile.lite --progress=plain --build-arg MODULE=${s} -t "vabags/${s}:dev" .
}
docker images "vabags/*"
