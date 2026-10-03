export type FrontendPluginApiVersion = '1.0';

export interface FrontendPluginManifestV1 {
  schema: 'ravenroot.frontend-plugin/v1';
  id: string;
  name: string;
  version: string;
  apiVersion: FrontendPluginApiVersion;
  description?: string;
  permissions: [];
  layouts?: ProviderManifest[];
  renderers?: ProviderManifest[];
  drawingModels?: DrawingModelManifest[];
  integrity?: Record<string, `sha256-${string}`>;
}

export interface ProviderManifest {
  id: string;
  name: string;
  entry: string;
  capabilities?: Array<'directed-edges' | 'self-loops' | 'parallel-edges' | 'opposite-edges'
    | 'initial-marker' | 'accepting-marker' | 'structured-labels' | 'selection'
    | 'operational-overlay' | 'authoring'>;
  requires?: ProviderManifest['capabilities'];
}

export interface DrawingModelManifest { id: string; name: string; layout: string; renderer: string }

export interface PresentationMappingV1 {
  schema: 'ravenroot.presentation-mapping/v1';
  states: Array<{ id: string; label: string; nodeId: string; initial?: boolean; accepting?: boolean }>;
  transitions: Array<{ id: string; source: string; target: string; label: string; edgePath: string[] }>;
  positions?: Record<string, { x: number; y: number }>;
}

export interface DrawingModelSnapshotV1 {
  schema: 'ravenroot.drawing-model-snapshot/v1';
  states: Array<PresentationMappingV1['states'][number] & { active: boolean }>;
  transitions: Array<PresentationMappingV1['transitions'][number] & { active: boolean }>;
  positions: Record<string, { x: number; y: number }>;
  evidence: { activeNodeIds: string[]; activeEdgeIds: string[]; description: string };
}

export interface LayoutResultV1 {
  schema: 'ravenroot.layout-result/v1';
  positions: Record<string, { x: number; y: number }>;
}

export interface SceneV1 {
  schema: 'ravenroot.scene/v1';
  width?: number;
  height?: number;
  elements: Array<{
    type: 'circle' | 'path' | 'text' | 'line'; id?: string; role?: 'state' | 'transition';
    label?: string; text?: string; d?: string; className?: string;
    x?: number; y?: number; x1?: number; y1?: number; x2?: number; y2?: number; r?: number;
  }>;
}

export interface LayoutProviderV1 {
  layout(snapshot: DrawingModelSnapshotV1, context: { signalId: number }): LayoutResultV1 | Promise<LayoutResultV1>;
}

export interface RendererProviderV1 {
  render(input: { snapshot: DrawingModelSnapshotV1; layout: LayoutResultV1 }, context: { signalId: number }): SceneV1 | Promise<SceneV1>;
}

export default interface FrontendPluginProviderV1 {
  layout?: LayoutProviderV1['layout'];
  render?: RendererProviderV1['render'];
}
