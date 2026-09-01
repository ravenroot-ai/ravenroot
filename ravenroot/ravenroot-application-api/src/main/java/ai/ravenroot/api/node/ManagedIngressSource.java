package ai.ravenroot.api.node;

import ai.ravenroot.api.deployment.InboundSource;
import ai.ravenroot.api.ingress.IngressRouteAuthority;
import java.util.concurrent.CompletionStage;

/** Source opt-in for managed HTTP routes, called only after its ordinary start reached readiness. */
public interface ManagedIngressSource extends InboundSource {
    /**
     * Activates routes using a deployment-issued authority after the source has reached readiness.
     *
     * @param authority attenuated authority for routes owned by this source; implementations must not
     *                  manufacture it from request data
     * @return a stage that completes when route activation has succeeded or failed
     */
    CompletionStage<Void> activateManagedIngress(IngressRouteAuthority authority);
}
