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
| `api/daily-report.mjs` | Daily WhatsApp portfolio report (Vercel Cron), see section 3 |
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

## 3. Daily WhatsApp report (optional)

Every weekday at **4:15 PM IST** (after the market closes) Vercel runs
`api/daily-report.mjs`. It reads your purchases from Supabase and works out:
- today's gain or loss in ₹ and % for each stock and for the whole portfolio,
- your overall gain or loss,
- today's move in the Nifty 50, Midcap 150 and Smallcap 250.

It then looks up news for your 5 biggest movers on Google News and asks a free
LLM to explain the moves in a few lines. Finally it sends everything to your
WhatsApp. The numbers are calculated in code; the LLM only writes the "why it
moved" part. If the LLM fails, you still get the numbers and the top headlines.
On market holidays nothing is sent.

**Set up (one time):**

1. **WhatsApp (CallMeBot, free):** go to callmebot.com → *WhatsApp API* and
   follow the steps there. You save their number in your contacts and send it the
   activation message from WhatsApp; it replies with your **API key**.
2. **Free LLM (Google Gemini):** go to aistudio.google.com → **Get API key** →
   **Create API key**. Any other service with an OpenAI-style chat API works too
   (Groq, OpenRouter, …): set `LLM_BASE_URL` and `LLM_MODEL` for it.
3. **Supabase secret key:** Supabase → **Project Settings → API Keys** → copy the
   **secret** key (`sb_secret_...`). It can read every user's data, so put it
   only in Vercel's settings below, never in `public/config.js` or GitHub.
4. **Your user id:** Supabase → **Authentication → Users** → click your email →
   copy the **User UID**.
5. In **Vercel → your project → Settings → Environment Variables**, add:

   | Name | Value |
   |---|---|
   | `CRON_SECRET` | any long random password you make up (Vercel Cron sends it) |
   | `SUPABASE_URL` | the Project URL, same as in `public/config.js` |
   | `SUPABASE_SECRET_KEY` | the secret key from step 3 |
   | `REPORT_USER_ID` | the User UID from step 4 |
   | `WHATSAPP_PHONE` | your number with country code, e.g. `+919876543210` |
   | `CALLMEBOT_API_KEY` | the key from step 1 |
   | `LLM_API_KEY` | the key from step 2 |
   | `LLM_MODEL` | optional; default `gemini-2.5-flash`. Use whatever free model AI Studio lists now. |
   | `LLM_BASE_URL` | optional; default is Gemini's. For Groq: `https://api.groq.com/openai/v1` |

6. Redeploy (**Deployments → ⋯ → Redeploy**) so the settings take effect.

**Try it:** open
`https://your-name.vercel.app/api/daily-report?secret=YOUR_CRON_SECRET&preview=1&force=1`
to see the message without sending it. Remove `&preview=1` to send it to
WhatsApp now. (`force=1` makes it run on days the market was closed.) The answer
shows `llmError` if the LLM part failed. To change the time, edit `crons` in
`vercel.json`; the schedule is in UTC (10:45 UTC = 4:15 PM IST).

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
