/** OAuth 2.0 client-credentials grant against the enterprise IdP (docs/phase-1-execution-spec.md
 * slice 2): the build-worker's service identity toward control-plane, scope `pdlc.build` by
 * default. The access token is cached until one minute before it expires. Mirrors
 * `ai.pdlc.adapters.serviceauth.ClientCredentialsTokenSource` on the Java side. */
export interface OAuthClientConfig {
  /** PDLC_OAUTH_TOKEN_URL - the IdP's token endpoint. */
  tokenUrl: string;
  /** PDLC_OAUTH_CLIENT_ID */
  clientId: string;
  /** PDLC_OAUTH_CLIENT_SECRET */
  clientSecret: string;
  /** PDLC_OAUTH_SCOPE - defaults to `pdlc.build`. */
  scope: string;
}

const REFRESH_MARGIN_MS = 60_000;

export class ClientCredentialsToken {
  private token?: string;
  private refreshAt = 0;
  private inflight?: Promise<string>;

  constructor(
    private readonly cfg: OAuthClientConfig,
    private readonly now: () => number = Date.now,
  ) {}

  async get(): Promise<string> {
    if (this.token && this.now() < this.refreshAt) {
      return this.token;
    }
    this.inflight ??= this.fetchToken().finally(() => {
      this.inflight = undefined;
    });
    return this.inflight;
  }

  private async fetchToken(): Promise<string> {
    const form = new URLSearchParams({ grant_type: 'client_credentials' });
    if (this.cfg.scope) {
      form.set('scope', this.cfg.scope);
    }
    // RFC 6749 §2.3.1: client id/secret are form-urlencoded before Basic encoding.
    const basic = Buffer.from(
      `${encodeURIComponent(this.cfg.clientId)}:${encodeURIComponent(this.cfg.clientSecret)}`,
    ).toString('base64');
    const res = await fetch(this.cfg.tokenUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded', Authorization: `Basic ${basic}` },
      body: form.toString(),
    });
    if (res.status !== 200) {
      throw new Error(`token endpoint ${this.cfg.tokenUrl} returned ${res.status}`);
    }
    const body = (await res.json()) as { access_token?: string; expires_in?: number };
    if (!body.access_token) {
      throw new Error(`token endpoint ${this.cfg.tokenUrl} returned no access_token`);
    }
    const expiresInMs = (body.expires_in ?? 300) * 1000;
    this.token = body.access_token;
    this.refreshAt = this.now() + Math.max(0, expiresInMs - REFRESH_MARGIN_MS);
    return this.token;
  }
}
