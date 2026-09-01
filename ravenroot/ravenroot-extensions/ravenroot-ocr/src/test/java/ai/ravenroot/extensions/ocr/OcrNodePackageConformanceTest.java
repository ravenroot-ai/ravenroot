package ai.ravenroot.extensions.ocr;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.testkit.api.NodeBehaviorContract;

import java.util.Map;

class OcrNodePackageConformanceTest extends NodeBehaviorContract {
    @Override protected NodePackage nodePackage() { return new OcrNodePackage(); }
    @Override protected NodeConfiguration configurationFor(NodeTypeDescriptor descriptor) {
        return new NodeConfiguration("ocr", descriptor.behavior(),
                Map.of("ocrProfile", OcrTestSupport.PROFILE, "language", "eng"));
    }
}
