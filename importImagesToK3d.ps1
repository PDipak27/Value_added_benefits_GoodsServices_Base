$services = @("api-gateway","order-service","inventory-service","billing-service", "notification-service","catalog-service","fulfilment-service","ott-service")
foreach ($s in $services) { k3d image import "vabags/${s}:dev" -c vabags }

