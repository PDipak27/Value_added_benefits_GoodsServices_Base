# Import the 4 Lite images into the k3d cluster's containerd store (they aren't pulled from a registry).
#   .\importLiteImagesToK3d.ps1   (assumes cluster name 'vabags')
$services = @("api-gateway", "lite-order-service", "lite-inventory-service", "lite-billing-service")
foreach ($s in $services) { k3d image import "vabags/${s}:dev" -c vabags }
#OR k3d image import image1:tag image2:tag image3:tag -c my-cluster