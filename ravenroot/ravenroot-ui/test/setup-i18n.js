import { createUiText } from '../src/ui-text.js';

// Some focused regression tests evaluate private app helpers in isolation. Give those extracted
// functions the same built-in English formatter used by the application.
globalThis.uiText = createUiText();
