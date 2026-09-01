/**
 * Ambient module declarations for the two Cytoscape extensions.
 *
 * Written here rather than pulled from `@types/cytoscape-edgehandles` and `@types/cytoscape-dagre`
 * deliberately: those packages version independently of the runtime ones, and a drift between them
 * breaks the build for no gain. Neither extension ships typings of its own.
 *
 * `cytoscape` itself does - since 3.3x it bundles `index.d.ts`, so TypeScript resolves the shipped
 * definitions and `@types/cytoscape` is neither installed nor consulted. Check
 * `node_modules/cytoscape/index.d.ts` when a name here needs verifying; the DefinitelyTyped package
 * has diverged from it (it still carries a `Stylesheet` alias the shipped types renamed
 * `StylesheetJson`).
 *
 * This file must stay free of top-level `import`/`export` statements. A `.d.ts` that has one becomes
 * a module, and `declare module 'x'` inside a module means *augment x* rather than *declare x* -
 * which is exactly why the `Core.edgehandles` augmentation lives in its own file beside this one.
 */

declare module 'cytoscape-dagre' {
  import type { Ext } from 'cytoscape';
  const extension: Ext;
  export default extension;
}

declare module 'cytoscape-edgehandles' {
  import type { Ext } from 'cytoscape';
  const extension: Ext;
  export default extension;
}
