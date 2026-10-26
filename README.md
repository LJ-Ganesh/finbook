# Indian Stock Price & Portfolio

Look up NSE/BSE stock prices with charts, and track a portfolio against the
Nifty 50, Nifty Midcap 150 and Nifty Smallcap 250. Prices come from Yahoo Finance.
Each user signs in and their portfolio is saved to their own account (Supabase).

## Files

| Path | What it is |
|---|---|
| `public/index.html` | Stock lookup page (no sign-in needed) |
| `public/portfolio.html` | Portfolio page (sign-in required) |
| `public/config.js` | Your Supabase project URL and anon key |
| `supabase/schema.sql` | Creates the portfolio table and its privacy rules |
| `api/index.mjs` | Online server (Vercel Function): fetches prices from Yahoo for the pages |
| `vercel.json` | Vercel settings |
| `server.rb`, `Start Stock Price.command` | Run it on your Mac instead (double-click the .command file) |

## 1. Set up accounts (Supabase, free)

1. Sign up at supabase.com → **New project**. Pick a name, a database password
   (save it somewhere) and the **Mumbai** region → **Create new project**.
2. When it's ready, open **SQL Editor** → **New query**, paste everything from
   `supabase/schema.sql`, and click **Run**. It should say "Success. No rows returned".
3. Open **Project Settings → API** (or the **Connect** button) and copy:
   - **Project URL** (looks like `https://abcdefgh.supabase.co`)
   - **anon public** key (a long text starting with `eyJ`)

   Paste them into `public/config.js` in place of `PASTE_PROJECT_URL_HERE` and
   `PASTE_ANON_KEY_HERE`. The anon key is safe in a web page: the rules from
   step 2 stop anyone reading other people's portfolios. **Never use the
   `service_role` key here.**
4. Open **Authentication → URL Configuration**:
   - **Site URL**: your website, e.g. `https://your-name.vercel.app`
   - **Redirect URLs** → **Add URL**: `https://your-name.vercel.app/portfolio`
     and `http://127.0.0.1:8080/portfolio` (for the Mac version)

   These are where the "confirm your email" and "reset password" links send people.

## 2. Put it online (Vercel, free)

1. **Upload to GitHub**: on your repository page click **Add file → Upload files**,
   drag in everything inside this folder (`api`, `public`, `supabase`, `vercel.json`,
   `README.md`, and the rest) and click **Commit changes**.
2. **Connect Vercel** (first time only): sign up at vercel.com with GitHub →
   **Add New → Project** → import the repository → Framework Preset **Other** → **Deploy**.

Every commit to the main branch on GitHub redeploys the site automatically,
usually within a minute. Check progress under the project's **Deployments** tab.

## Caching

To keep things fast and avoid asking Yahoo too often:

| What | Browser keeps it | Vercel's network keeps it |
|---|---|---|
| Stock search results | 1 day (survives reloads) | 1 hour |
| Price quote | 30 seconds | 30 seconds |
| Price chart | 1 minute | 1 minute |
| Portfolio price history | 5 minutes | 10 minutes |

The Mac version (`server.rb`) keeps the same answers in memory.

## Good to know

- New accounts get a confirmation email first. Supabase's built-in email
  service only sends a few emails per hour; that's fine for personal use.
  To skip confirmation, turn off **Authentication → Sign In / Providers → Email
  → Confirm email**.
- Free Supabase projects pause after a week with no activity. If sign-in stops
  working, open supabase.com and click **Restore project**.
- Purchases saved in a browser before accounts existed are offered for moving
  into your account the first time you sign in there.
- Yahoo Finance is unofficial and sometimes slows down or blocks requests,
  especially from cloud servers. If prices stop loading online, try again later.
