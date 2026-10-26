# Indian Stock Price & Portfolio

Look up NSE/BSE stock prices with charts, and track a portfolio against the
Nifty 50, Nifty Midcap 150 and Nifty Smallcap 250. Prices come from Yahoo Finance.

## Files

| Path | What it is |
|---|---|
| `public/index.html` | Stock lookup page |
| `public/portfolio.html` | Portfolio page (purchases are saved in your browser) |
| `api/index.mjs` | Online server (Vercel Function): fetches prices from Yahoo for the pages |
| `vercel.json` | Vercel settings |
| `server.rb`, `Start Stock Price.command` | Run it on your Mac instead (double-click the .command file) |

## Put it online for free (Vercel)

1. **Upload to GitHub**
   1. Sign in at github.com → **New repository** → give it a name → **Create repository**.
   2. Click **uploading an existing file**.
   3. Open this folder in Finder, select everything inside it (`api`, `public`,
      `vercel.json`, `README.md`, and the rest) and drag it onto the page.
      Folders upload with their contents.
   4. Click **Commit changes**.
2. **Connect Vercel**
   1. Sign up at vercel.com with your GitHub account.
   2. **Add New → Project** → **Import** your repository.
   3. Framework Preset: **Other**. Leave everything else as it is → **Deploy**.
3. After a minute you get an address like `https://your-name.vercel.app`.

Every commit to the main branch on GitHub redeploys the site automatically,
usually within a minute. Check progress under the project's **Deployments** tab.

## Good to know

- Your portfolio is stored only in the browser you add it in. Use **Export** to
  back it up, and **Import** to load it on another device or browser.
- Yahoo Finance is unofficial and sometimes slows down or blocks requests,
  especially from cloud servers. If prices stop loading online, try again later.
- Vercel's free (Hobby) plan includes far more function calls each month than
  one person will use.
