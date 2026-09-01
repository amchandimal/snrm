import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { AuthService } from './core/auth.service';

/**
 * Application shell.
 *
 * A header and a router outlet. The header knows nothing except whether somebody is signed in - it
 * reads the `TokenStore` signals through `AuthService`, so login and logout redraw it with no event
 * plumbing.
 */
@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppComponent {
  private readonly auth = inject(AuthService);

  readonly isAuthenticated = this.auth.isAuthenticated;
  readonly username = this.auth.username;

  logout(): void {
    this.auth.logout();
  }
}
