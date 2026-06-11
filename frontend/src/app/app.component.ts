import { CommonModule } from "@angular/common";
import { HttpClient } from "@angular/common/http";
import { Component, inject, signal } from "@angular/core";
import { FormBuilder, ReactiveFormsModule, Validators } from "@angular/forms";

// Frontend data shapes returned by the backend API.
// These types help TypeScript check the HTTP response structure.
type ShortenResponse = {
  shortUrl: string;
  shortCode: string;
  originalUrl: string;
  createdAt: string;
  expiresAt: string | null;
};

type UrlStats = {
  shortCode: string;
  originalUrl: string;
  clickCount: number;
  createdAt: string;
  expiresAt: string | null;
  active: boolean;
  createdBy: string;
};

@Component({
  selector: "app-root",
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: "./app.component.html",
  styleUrls: ["./app.component.css"],
})
// Root component for the frontend app.
// It manages user input, sends backend API requests, and displays results.
export class AppComponent {
  // Angular's HttpClient is used to call backend API endpoints.
  private readonly http = inject(HttpClient);
  // FormBuilder helps create reactive forms and validation rules.
  private readonly formBuilder = inject(FormBuilder);

  // Form for creating a new short URL.
  protected readonly form = this.formBuilder.group({
    originalUrl: [
      "",
      [Validators.required, Validators.pattern(/^https?:\/\/.+/i)],
    ],
    customAlias: [""],
    expiresAt: [""],
  });

  // Form for fetching URL statistics by short code.
  protected readonly statsForm = this.formBuilder.group({
    shortCode: ["", Validators.required],
  });

  // UI state tracking for loading and error display using angular signals.
  protected readonly loading = signal(false);
  protected readonly statsLoading = signal(false);
  protected readonly error = signal("");
  protected readonly statsError = signal("");
  protected readonly result = signal<ShortenResponse | null>(null);
  protected readonly stats = signal<UrlStats | null>(null);

  // Called when the user submits the create URL form.
  // It sends the form data to the backend /api/shorten endpoint.
  protected shortenUrl(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const raw = this.form.getRawValue();
    const payload = {
      originalUrl: raw.originalUrl ?? "",
      customAlias: raw.customAlias || null,
      expiresAt: raw.expiresAt ? this.toLocalDateTime(raw.expiresAt) : null,
    };

    this.loading.set(true);
    this.error.set("");
    this.result.set(null);

    // Use the configured proxy so the frontend can call backend API via /api.
    this.http.post<ShortenResponse>("/api/shorten", payload).subscribe({
      next: (response) => {
        this.result.set(response); // show the short URL result in the UI
        this.statsForm.patchValue({ shortCode: response.shortCode }); // prefill stats form
        this.loading.set(false);
      },
      error: (error) => {
        this.error.set(error?.error?.error ?? "Failed to create short URL."); // show backend error
        this.loading.set(false);
      },
    });
  }

  // Called when the user submits the stats form.
  // It requests URL metadata from the backend and displays it in the UI.
  protected loadStats(): void {
    if (this.statsForm.invalid) {
      this.statsForm.markAllAsTouched();
      return;
    }

    const shortCode = this.statsForm.getRawValue().shortCode ?? "";

    this.statsLoading.set(true);
    this.statsError.set("");
    this.stats.set(null);

    // Fetch basic statistics for the short code from the backend API.
    this.http.get<UrlStats>(`/api/stats/${shortCode}`).subscribe({
      next: (response) => {
        this.stats.set(response); // display the backend response in the stats card
        this.statsLoading.set(false);
      },
      error: (error) => {
        this.statsError.set(error?.error?.error ?? "Failed to fetch stats."); // show error text
        this.statsLoading.set(false);
      },
    });
  }

  // Normalize the HTML datetime-local value so the backend receives a full timestamp.
  private toLocalDateTime(value: string): string {
    return value.length === 16 ? `${value}:00` : value;
  }
}
