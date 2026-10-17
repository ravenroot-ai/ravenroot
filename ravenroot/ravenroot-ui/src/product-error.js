/**
 * Keeps the developer-facing Error message useful while giving presentation code a stable,
 * localizable reason. Protocol fields and server diagnostics remain untouched.
 */
export function productError(uiMessageKey, message, uiMessageParams = {}) {
  const error = new Error(message);
  Object.defineProperties(error, {
    uiMessageKey: { value: uiMessageKey, enumerable: false },
    uiMessageParams: { value: Object.freeze({ ...uiMessageParams }), enumerable: false },
  });
  return error;
}
