import { Routes } from '@angular/router';

import { authGuard } from './core/auth.guard';
import { networkEditorGuard } from './features/network-editor/network-editor.guard';

/**
 * Route tree.
 *
 * Every feature is lazily loaded with `loadComponent`: the network editor will pull in Cytoscape
 * and the dashboards will pull in a charting library, and neither belongs in the bundle a
 * researcher downloads to reach the login form.
 *
 * Everything except the login sits behind {@link authGuard}.
 */
export const routes: Routes = [
  {
    path: '',
    pathMatch: 'full',
    redirectTo: 'projects',
  },
  {
    path: 'login',
    title: 'Sign in · SNRM',
    loadComponent: () =>
      import('./features/auth/login/login.component').then((m) => m.LoginComponent),
  },
  {
    path: 'projects',
    canActivate: [authGuard],
    children: [
      {
        path: '',
        title: 'Projects · SNRM',
        loadComponent: () =>
          import('./features/projects/project-list/project-list.component').then(
            (m) => m.ProjectListComponent,
          ),
      },
      {
        path: ':projectId',
        title: 'Project · SNRM',
        loadComponent: () =>
          import('./features/projects/project-dashboard/project-dashboard.component').then(
            (m) => m.ProjectDashboardComponent,
          ),
      },
      {
        // The product catalogue, scoped under the project because that is what a product
        // belongs to (`PRODUCT`) - every configuration variant of the project is a
        // different structure over this one catalogue, which is what keeps their cost metrics
        // comparable. It is not a section of the editor for that reason.
        path: ':projectId/products',
        title: 'Products · SNRM',
        loadComponent: () =>
          import('./features/projects/product-catalogue/product-catalogue.component').then(
            (m) => m.ProductCatalogueComponent,
          ),
      },
      {
        // The three-step import wizard. Scoped under the project because that is what an
        // import creates a network in, and what its product catalogue resolves against.
        path: ':projectId/import',
        title: 'Import network · SNRM',
        loadComponent: () =>
          import('./features/data-import/import-wizard.component').then(
            (m) => m.ImportWizardComponent,
          ),
      },
      {
        // The disruption scenarios, scoped under the project because that is what a
        // scenario belongs to - one story replayed against every configuration variant.
        path: ':projectId/scenarios',
        title: 'Scenarios · SNRM',
        loadComponent: () =>
          import('./features/scenario-builder/scenario-list/scenario-list.component').then(
            (m) => m.ScenarioListComponent,
          ),
      },
      {
        // The Gantt-style event timeline. The network the timeline is laid against is a
        // picker inside the page, not a route parameter: switching it re-lays the same scenario
        // against a different clock and changes no event, so it is not a different place to be.
        path: ':projectId/scenarios/:scenarioId',
        title: 'Scenario builder · SNRM',
        loadComponent: () =>
          import('./features/scenario-builder/scenario-builder.component').then(
            (m) => m.ScenarioBuilderComponent,
          ),
      },
      {
        // The run launcher and job monitor, scoped to the project because a run needs a
        // network *and* a scenario and the scenario is project-scoped.
        path: ':projectId/simulations',
        pathMatch: 'full',
        title: 'Run simulation · SNRM',
        loadComponent: () =>
          import('./features/simulations/run-launcher/run-launcher.component').then(
            (m) => m.RunLauncherComponent,
          ),
      },
      {
        // The results dashboard. Takes an optional `?jobId=` so a page opened straight
        // from the launcher can show live progress and a cancel button; without it the run is still
        // readable, because the job id is only ever handed to whoever submitted it.
        path: ':projectId/simulations/:runId',
        title: 'Run results · SNRM',
        loadComponent: () =>
          import('./features/simulations/results-dashboard/results-dashboard.component').then(
            (m) => m.ResultsDashboardComponent,
          ),
      },
      {
        // The comparison view. Project-scoped because a comparison is about several
        // networks at once and no one of them owns it.
        path: ':projectId/comparison',
        title: 'Compare configurations · SNRM',
        loadComponent: () =>
          import('./features/comparison/comparison.component').then((m) => m.ComparisonComponent),
      },
      {
        // The structural comparison (FR-25) - the selected networks side by side, one
        // read-only miniature per pane. Under `comparison/` because it is the second reading of one
        // question: the matrix above answers how these configurations *performed*, and this answers
        // how they are *shaped* - two readings kept deliberately distinct.
        //
        // **The networks are on the query string, not in memory.** The FR-23 actions menu routes
        // here, and it has to be a real address: one that survives a reload, can be bookmarked and
        // can be sent to somebody else. `pane-grid.parsePaneIds` is what reads it,
        // including every way a link can be stale by the time it is opened.
        path: ':projectId/comparison/structure',
        title: 'Networks side by side · SNRM',
        loadComponent: () =>
          import('./features/comparison/side-by-side/side-by-side.component').then(
            (m) => m.SideBySideComponent,
          ),
      },
      {
        // The Cytoscape canvas. `canDeactivate` flushes pending canvas edits on the way
        // out, which is the "on blur" half of the debounced persistence.
        path: ':projectId/networks/:networkId/editor',
        title: 'Network editor · SNRM',
        canDeactivate: [networkEditorGuard],
        loadComponent: () =>
          import('./features/network-editor/network-editor.component').then(
            (m) => m.NetworkEditorComponent,
          ),
      },
    ],
  },
  {
    path: '**',
    redirectTo: 'projects',
  },
];
