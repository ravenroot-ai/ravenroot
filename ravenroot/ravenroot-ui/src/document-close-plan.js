import { sourceSessionIsActive } from './source-session.js';

/** Capture the exact open-document incarnations targeted by one bulk-close invocation. */
export function captureDocumentCloseSnapshot(documents = []) {
  return Object.freeze(documents.map(document_ => Object.freeze({
    id: document_.id,
    incarnation: document_.incarnation,
    document: document_,
  })));
}

/** Resolve only records that are still the same open incarnation. */
export function resolveDocumentCloseSnapshot(workspace, snapshot = []) {
  return snapshot.map(entry => {
    const current = workspace.find(entry.id);
    return current === entry.document && current?.incarnation === entry.incarnation ? current : null;
  }).filter(Boolean);
}

export function classifyDocumentCloseTargets(documents = []) {
  return {
    dirty: documents.filter(document_ => Boolean(document_.history?.isDirty())),
    activeSessions: documents.filter(document_ => sourceSessionIsActive(document_.sourceSession)),
  };
}
