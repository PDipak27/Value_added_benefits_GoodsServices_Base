$services = @("api-gateway","order-service","inventory-service","billing-service", "notification-service","catalog-service","fulfilment-service","ott-service")
foreach ($s in $services) { docker build --no-cache --progress=plain --build-arg MODULE=${s} -t "vabags/${s}:dev" . }
docker images vabags/*
