import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';

import { AuthService } from '../../../core/auth.service';
import { problemMessage } from '../../../core/problem-details';

/**
 * Login page - `POST /auth/login`.
 *
 * Phase 1 authenticates one research user, so this is a username, a password and nothing
 * else: no registration, no password reset, no roles.
 *
 * The app has six feature folders and none of them is a home for the login screen;
 * it lives here as a seventh, `features/auth/`, rather than in `core/`, which is scoped to
 * infrastructure (interceptor, token store, ApiService, JobPollingService).
 */
@Component({
  selector: 'app-login',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './login.component.html',
  styleUrl: './login.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LoginComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly formBuilder = inject(NonNullableFormBuilder);

  /** `researcher` is the backend's default `snrm.auth.username`; the password is never prefilled. */
  readonly form = this.formBuilder.group({
    username: ['researcher', [Validators.required]],
    password: ['', [Validators.required]],
  });

  readonly submitting = signal(false);
  readonly error = signal<string | null>(null);

  submit(): void {
    if (this.form.invalid || this.submitting()) {
      this.form.markAllAsTouched();
      return;
    }

    this.submitting.set(true);
    this.error.set(null);

    this.auth.login(this.form.getRawValue()).subscribe({
      next: () => {
        this.submitting.set(false);
        void this.router.navigateByUrl(this.redirectTarget());
      },
      error: (failure: unknown) => {
        this.submitting.set(false);
        this.error.set(problemMessage(failure, 'Login failed.'));
      },
    });
  }

  /** Where the guard wanted to go before it bounced the user here. */
  private redirectTarget(): string {
    const requested = this.route.snapshot.queryParamMap.get('redirectTo');
    return requested && !requested.startsWith('/login') ? requested : '/projects';
  }
}
