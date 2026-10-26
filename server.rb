# Tiny local server for the Indian stock price page.
# Uses only the Ruby that ships with macOS: no installs needed.
# Browsers can't call Yahoo Finance directly, so the page asks this server,
# and this server asks Yahoo. Online, netlify/functions/api.mjs does the same job.
require 'webrick'
require 'net/http'
require 'json'
require 'uri'
require 'date'

PORT = 8080
ROOT = File.join(__dir__, 'public')
INDIAN_EXCHANGES = { 'NSI' => 'NSE', 'BSE' => 'BSE' }.freeze
SYMBOL_PATTERN = /\A[A-Z0-9&\-]+\.(NS|BO)\z/
# Chart button -> Yahoo options. Yahoo has no 3-year range, so 3Y asks for
# the dates from 3 years ago until now instead.
CHART_RANGES = {
  '1D' => { range: '1d',  interval: '5m' },
  '1W' => { range: '5d',  interval: '30m' },
  '1M' => { range: '1mo', interval: '1d' },
  '3M' => { range: '3mo', interval: '1d' },
  '6M' => { range: '6mo', interval: '1d' },
  '1Y' => { range: '1y',  interval: '1d' },
  '3Y' => { years: 3,     interval: '1wk' }
}.freeze
# Indices to compare the portfolio against. Yahoo only has history for these
# broad Nifty indices (Midcap 100, Smallcap 100 and Microcap 250 have no history there).
INDICES = {
  '^NSEI' => 'Nifty 50',
  'NIFTYMIDCAP150.NS' => 'Nifty Midcap 150',
  'NIFTYSMLCAP250.NS' => 'Nifty Smallcap 250'
}.freeze
HISTORY_CACHE = {}
HISTORY_TTL = 600 # seconds to reuse downloaded price history

def yahoo(path, params)
  uri = URI("https://query1.finance.yahoo.com#{path}")
  uri.query = URI.encode_www_form(params)
  res = Net::HTTP.start(uri.host, uri.port, use_ssl: true, open_timeout: 10, read_timeout: 15) do |http|
    http.get(uri.request_uri, 'User-Agent' => 'Mozilla/5.0', 'Accept' => 'application/json')
  end
  raise 'Yahoo is rate-limiting requests. Wait a minute and try again.' if res.code == '429'
  JSON.parse(res.body)
end

# Daily closing prices from a date until today, as [["2025-01-31", 1234.5], ...]
# in Indian dates. Starts a week early so the day before `from` is included.
def daily_history(symbol, from)
  key = "#{symbol}|#{from}"
  cached = HISTORY_CACHE[key]
  return cached[:points] if cached && Time.now - cached[:at] < HISTORY_TTL

  start = Time.new(from.year, from.month, from.day, 0, 0, 0, '+05:30').to_i - 7 * 86_400
  chart = yahoo("/v8/finance/chart/#{URI.encode_www_form_component(symbol)}",
                period1: start, period2: Time.now.to_i, interval: '1d')['chart']
  result = chart.dig('result', 0)
  raise(chart.dig('error', 'description') || "No price history for #{symbol}.") unless result && result['timestamp']

  closes = result.dig('indicators', 'quote', 0, 'close') || []
  by_day = {}
  result['timestamp'].zip(closes).each do |t, c|
    by_day[Time.at(t).getlocal('+05:30').strftime('%Y-%m-%d')] = c unless c.nil?
  end
  points = by_day.to_a
  HISTORY_CACHE[key] = { at: Time.now, points: points }
  points
end

def send_json(res, status, body)
  res.status = status
  res['Content-Type'] = 'application/json'
  res.body = JSON.generate(body)
end

server = WEBrick::HTTPServer.new(Port: PORT, BindAddress: '127.0.0.1',
                                 AccessLog: [], Logger: WEBrick::Log.new($stderr, WEBrick::Log::WARN))

server.mount_proc('/') do |_req, res|
  res['Content-Type'] = 'text/html; charset=utf-8'
  res.body = File.read(File.join(ROOT, 'index.html'))
end

# Company name or ticker -> matching NSE/BSE stocks, NSE first.
server.mount_proc('/api/search') do |req, res|
  q = req.query['q'].to_s.strip
  next send_json(res, 400, error: 'Enter a stock name.') if q.empty?
  begin
    data = yahoo('/v1/finance/search', q: q, quotesCount: 15, newsCount: 0, region: 'IN')
    matches = (data['quotes'] || [])
              .select { |s| s['quoteType'] == 'EQUITY' && INDIAN_EXCHANGES.key?(s['exchange']) }
              .sort_by { |s| s['exchange'] == 'NSI' ? 0 : 1 }
              .map { |s| { symbol: s['symbol'], name: s['longname'] || s['shortname'], exchange: INDIAN_EXCHANGES[s['exchange']] } }
    send_json(res, 200, matches: matches)
  rescue StandardError => e
    send_json(res, 502, error: e.message)
  end
end

server.mount_proc('/api/quote') do |req, res|
  symbol = req.query['symbol'].to_s.strip.upcase
  next send_json(res, 400, error: 'Only NSE (.NS) and BSE (.BO) stocks are supported.') unless symbol =~ SYMBOL_PATTERN
  begin
    chart = yahoo("/v8/finance/chart/#{symbol}", range: '1d', interval: '1d')['chart']
    meta = chart.dig('result', 0, 'meta')
    next send_json(res, 404, error: chart.dig('error', 'description') || 'No price found.') unless meta && meta['regularMarketPrice']
    price = meta['regularMarketPrice']
    prev = meta['chartPreviousClose'] || meta['previousClose']
    send_json(res, 200,
              symbol: symbol,
              name: meta['longName'] || meta['shortName'] || symbol,
              exchange: meta['fullExchangeName'],
              price: price,
              previousClose: prev,
              change: prev ? price - prev : nil,
              changePercent: prev && prev != 0 ? (price - prev) / prev * 100 : nil,
              dayHigh: meta['regularMarketDayHigh'],
              dayLow: meta['regularMarketDayLow'],
              yearHigh: meta['fiftyTwoWeekHigh'],
              yearLow: meta['fiftyTwoWeekLow'],
              volume: meta['regularMarketVolume'],
              time: meta['regularMarketTime'])
  rescue StandardError => e
    send_json(res, 502, error: e.message)
  end
end

# Price history for the chart: a list of [time, closing price] points.
server.mount_proc('/api/chart') do |req, res|
  symbol = req.query['symbol'].to_s.strip.upcase
  choice = CHART_RANGES[req.query['range'].to_s.upcase]
  next send_json(res, 400, error: 'Only NSE (.NS) and BSE (.BO) stocks are supported.') unless symbol =~ SYMBOL_PATTERN
  next send_json(res, 400, error: "Range must be one of #{CHART_RANGES.keys.join(', ')}.") unless choice
  begin
    params = { interval: choice[:interval] }
    if choice[:years]
      params[:period2] = Time.now.to_i
      params[:period1] = params[:period2] - choice[:years] * 365 * 86_400
    else
      params[:range] = choice[:range]
    end
    chart = yahoo("/v8/finance/chart/#{symbol}", params)['chart']
    result = chart.dig('result', 0)
    times = result && result['timestamp']
    next send_json(res, 404, error: chart.dig('error', 'description') || 'No price history found.') unless times
    closes = result.dig('indicators', 'quote', 0, 'close') || []
    # Yahoo leaves gaps (nil) for moments with no trades; drop those.
    points = times.zip(closes).reject { |_t, c| c.nil? }
    send_json(res, 200, symbol: symbol, range: req.query['range'].upcase,
                        previousClose: result.dig('meta', 'chartPreviousClose'), points: points)
  rescue StandardError => e
    send_json(res, 502, error: e.message)
  end
end

server.mount_proc('/portfolio') do |_req, res|
  res['Content-Type'] = 'text/html; charset=utf-8'
  res.body = File.read(File.join(ROOT, 'portfolio.html'))
end

server.mount_proc('/api/indices') do |_req, res|
  send_json(res, 200, indices: INDICES.map { |symbol, name| { symbol: symbol, name: name } })
end

server.mount_proc('/api/history') do |req, res|
  symbol = req.query['symbol'].to_s.strip.upcase
  from = Date.strptime(req.query['from'].to_s, '%Y-%m-%d') rescue nil
  next send_json(res, 400, error: 'Unknown symbol.') unless symbol =~ SYMBOL_PATTERN || INDICES.key?(symbol)
  next send_json(res, 400, error: 'from must be a date like 2025-01-31.') unless from
  begin
    send_json(res, 200, symbol: symbol, points: daily_history(symbol, from))
  rescue StandardError => e
    send_json(res, 502, error: e.message)
  end
end

trap('INT') { server.shutdown }
trap('TERM') { server.shutdown }
puts "Stock price page running at http://127.0.0.1:#{PORT}  (press Ctrl+C to stop)"
server.start
