package ai.ravenroot.extensions.ocr;

import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;

import java.util.List;

/** Optional, operator-profiled local OCR package. */
public final class OcrNodePackage implements NodePackage {
    @Override public String id() { return "ai.ravenroot.extensions.ocr"; }
    @Override public String version() { return "1.0.0"; }
    @Override public String sdkContract() { return NodeSdk.CONTRACT; }
    @Override public List<NodeBehavior> behaviors() { return List.of(new OcrExtractNodeBehavior()); }
}
