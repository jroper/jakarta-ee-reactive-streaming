package controllers;

import com.lightbend.lagom.javadsl.api.ServiceAcl;
import com.lightbend.lagom.javadsl.api.ServiceInfo;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Produces;

@ApplicationScoped
public class WebGatewayServiceInfo {

    @Produces
    public ServiceInfo serviceInfo() {
        return ServiceInfo.of("web-gateway-module", ServiceAcl.path("(?!/api/).*"));
    }
}
