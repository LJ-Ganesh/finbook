# Indian Stock Price & Portfolio

Look up NSE/BSE stock prices with charts, and track a portfolio against the
Nifty 50, Nifty Midcap 150 and Nifty Smallcap 250. Prices come from Yahoo Finance.

## Files

| Path | What it is |
|---|---|
| `public/index.html` | Stock lookup page |
| `public/portfolio.html` | Portfolio page (purchases are saved in your browser) |
| `netlify/functions/api.mjs` | Online server: fetches prices from Yahoo for the pages |
| `netlify.toml` | Netlify settings |
| `server.rb`, `Start Stock Price.command` | Run it on your Mac instead (double-click the .command file) |

## Put it online for free (Netlify)

1. **Upload to GitHub**
   1. Sign in at github.com → **New repository** → give it a name → **Create repository**.
   2. Click **uploading an existing file**.
   3. Open this folder in Finder, select everything inside it (`public`, `netlify`,
      `netlify.toml`, `README.md`, and the rest) and drag it onto the page.
      Folders upload with their contents.
   4. Click **Commit changes**.
2. **Connect Netlify**
   1. Sign up at netlify.com with your GitHub account.
   2. **Add new project → Import an existing project → GitHub** → pick your repository.
   3. Leave the settings as they are (they come from `netlify.toml`) → **Deploy**.
3. After a minute you get an address like `https://your-name.netlify.app`.
   Change it under **Project configuration → Change project name**.

To update the site later, upload the changed files to GitHub again; Netlify
redeploys by itself.

## Good to know

- Your portfolio is stored only in the browser you add it in. Use **Export** to
  back it up, and **Import** to load it on another device or browser.
- Yahoo Finance is unofficial and sometimes slows down or blocks requests,
  especially from cloud servers. If prices stop loading online, try again later.
- Netlify's free plan includes far more function calls each month than one
  person will use.
