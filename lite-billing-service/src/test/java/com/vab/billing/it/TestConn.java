package com.vab.billing.it;
import org.testcontainers.DockerClientFactory;
public class TestConn {
	

    public static void main(String[] args) {
        // This forces Testcontainers to validate the environment and host
        boolean isDockerAvailable = DockerClientFactory.instance().isDockerAvailable();
        System.out.println("Docker Available: " + isDockerAvailable);
    }

}
