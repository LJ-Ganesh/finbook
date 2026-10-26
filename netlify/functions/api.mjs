// Netlify Function: the online version of server.rb.
// Browsers can't call Yahoo Finance directly, so the pages ask /api/... here,
// and this function asks Yahoo. Every /api/* request comes to this one file.

const INDIAN_EXCHANGES = { NSI: 'NSE', BSE: 'BSE' };
const SYMBOL_PATTERN = /^[A-Z0-9&-]+\.(NS|BO)$/;
// Chart button -> Yahoo options. Yahoo has no 3-year range, so 3Y asks for
// the dates from 3 years ago until now instead.
const CHART_RANGES = {
  '1D': { range: '1d', interval: '5m' },
  '1W': { range: '5d', interval: '30m' },
  '1M': { range: '1mo', interval: '1d' },
  '3M': { range: '3mo', interval: '1d' },
  '6M': { range: '6mo', interval: '1d' },
  '1Y': { range: '1y', interval: '1d' },
  '3Y': { years: 3, interval: '1wk' }
};
// Indices to compare the portfolio against. Yahoo only has history for these
// broad Nifty indices (Midcap 100, Smallcap 100 and Microcap 250 have no history there).
const INDICES = {
  '^NSEI': 'Nifty 50',
  'NIFTYMIDCAP150.NS': 'Nifty Midcap 150',
  'NIFTYSMLCAP250.NS': 'Nifty Smallcap 250'
};
const IST_OFFSET = 19800; // +05:30 in seconds

async function yahoo(path, params) {
  const url = `https://query1.finance.yahoo.com${path}?${new URLSearchParams(params)}`;
  const res = await fetch(url, { headers: { 'User-Agent': 'Mozilla/5.0', Accept: 'application/json' } });
  if (res.status === 429) throw new Error('Yahoo is rate-limiting requests. Wait a minute and try again.');
  return res.json();
}

// cacheSeconds lets Netlify's CDN reuse an answer instead of asking Yahoo again.
function json(status, body, cacheSeconds = 0) {
  const headers = { 'Content-Type': 'application/json' };
  if (status === 200 && cacheSeconds) headers['Netlify-CDN-Cache-Control'] = `public, s-maxage=${cacheSeconds}`;
  return new Response(JSON.stringify(body), { status, headers });
}

const chartPath = symbol => `/v8/finance/chart/${encodeURIComponent(symbol)}`;

// Company name or ticker -> matching NSE/BSE stocks, NSE first.
async function search(params) {
  const q = (params.get('q') || '').trim();
  if (!q) return json(400, { error: 'Enter a stock name.' });
  const data = await yahoo('/v1/finance/search', { q, quotesCount: 15, newsCount: 0, region: 'IN' });
  const matches = (data.quotes || [])
    .filter(s => s.quoteType === 'EQUITY' && INDIAN_EXCHANGES[s.exchange])
    .sort((a, b) => (a.exchange === 'NSI' ? 0 : 1) - (b.exchange === 'NSI' ? 0 : 1))
    .map(s => ({ symbol: s.symbol, name: s.longname || s.shortname, exchange: INDIAN_EXCHANGES[s.exchange] }));
  return json(200, { matches }, 3600);
}

async function quote(params) {
  const symbol = (params.get('symbol') || '').trim().toUpperCase();
  if (!SYMBOL_PATTERN.test(symbol)) return json(400, { error: 'Only NSE (.NS) and BSE (.BO) stocks are supported.' });
  const chart = (await yahoo(chartPath(symbol), { range: '1d', interval: '1d' })).chart || {};
  const meta = chart.result?.[0]?.meta;
  if (!meta?.regularMarketPrice) return json(404, { error: chart.error?.description || 'No price found.' });
  const price = meta.regularMarketPrice;
  const prev = meta.chartPreviousClose ?? meta.previousClose ?? null;
  return json(200, {
    symbol,
    name: meta.longName || meta.shortName || symbol,
    exchange: meta.fullExchangeName,
    price,
    previousClose: prev,
    change: prev != null ? price - prev : null,
    changePercent: prev ? (price - prev) / prev * 100 : null,
    dayHigh: meta.regularMarketDayHigh,
    dayLow: meta.regularMarketDayLow,
    yearHigh: meta.fiftyTwoWeekHigh,
    yearLow: meta.fiftyTwoWeekLow,
    volume: meta.regularMarketVolume,
    time: meta.regularMarketTime
  }, 30);
}

// Price history for the chart: a list of [time, closing price] points.
async function chartData(params) {
  const symbol = (params.get('symbol') || '').trim().toUpperCase();
  const rangeKey = (params.get('range') || '').toUpperCase();
  const choice = CHART_RANGES[rangeKey];
  if (!SYMBOL_PATTERN.test(symbol)) return json(400, { error: 'Only NSE (.NS) and BSE (.BO) stocks are supported.' });
  if (!choice) return json(400, { error: `Range must be one of ${Object.keys(CHART_RANGES).join(', ')}.` });
  const query = { interval: choice.interval };
  if (choice.years) {
    query.period2 = Math.floor(Date.now() / 1000);
    query.period1 = query.period2 - choice.years * 365 * 86400;
  } else query.range = choice.range;
  const chart = (await yahoo(chartPath(symbol), query)).chart || {};
  const result = chart.result?.[0];
  if (!result?.timestamp) return json(404, { error: chart.error?.description || 'No price history found.' });
  const closes = result.indicators?.quote?.[0]?.close || [];
  // Yahoo leaves gaps (null) for moments with no trades; drop those.
  const points = result.timestamp.map((t, i) => [t, closes[i]]).filter(p => p[1] != null);
  return json(200, { symbol, range: rangeKey, previousClose: result.meta?.chartPreviousClose, points }, 60);
}

// Daily closing prices from a date until today, as [["2025-01-31", 1234.5], ...]
// in Indian dates. Starts a week early so the day before `from` is included.
async function history(params) {
  const symbol = (params.get('symbol') || '').trim().toUpperCase();
  const from = params.get('from') || '';
  if (!SYMBOL_PATTERN.test(symbol) && !INDICES[symbol]) return json(400, { error: 'Unknown symbol.' });
  if (!/^\d{4}-\d{2}-\d{2}$/.test(from)) return json(400, { error: 'from must be a date like 2025-01-31.' });
  const start = Date.parse(from + 'T00:00:00Z') / 1000 - IST_OFFSET - 7 * 86400;
  const chart = (await yahoo(chartPath(symbol), { period1: start, period2: Math.floor(Date.now() / 1000), interval: '1d' })).chart || {};
  const result = chart.result?.[0];
  if (!result?.timestamp) return json(502, { error: chart.error?.description || `No price history for ${symbol}.` });
  const closes = result.indicators?.quote?.[0]?.close || [];
  const byDay = new Map();
  result.timestamp.forEach((t, i) => {
    if (closes[i] != null) byDay.set(new Date((t + IST_OFFSET) * 1000).toISOString().slice(0, 10), closes[i]);
  });
  return json(200, { symbol, points: [...byDay] }, 600);
}

const ROUTES = {
  search,
  quote,
  chart: chartData,
  history,
  indices: async () => json(200, { indices: Object.entries(INDICES).map(([symbol, name]) => ({ symbol, name })) }, 86400)
};

export default async req => {
  const url = new URL(req.url);
  const route = ROUTES[url.pathname.replace(/^\/api\//, '').replace(/\/$/, '')];
  if (!route) return json(404, { error: 'Unknown address.' });
  try {
    return await route(url.searchParams);
  } catch (err) {
    return json(502, { error: err.message || 'Could not reach Yahoo Finance.' });
  }
};

export const config = { path: '/api/*' };
