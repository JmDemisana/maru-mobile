export function applyMarucastBrowserSenderAnswer(_raw: string): Promise<void> { return Promise.resolve(); }
export function getMarucastBrowserState() { return { mode: "idle" as const, connectionState: null as string | null }; }
export function prepareMarucastBrowserLivePcmSender(_url: string): Promise<void> { return Promise.resolve(); }
export function stopMarucastBrowserSession(): void { /* no-op */ }
export function updateMarucastBrowserSenderMetadata(_meta: unknown): void { /* no-op */ }
