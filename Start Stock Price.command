#!/bin/bash
# Double-click to start the stock price page. Close this window to stop it.
cd "$(dirname "$0")"
(sleep 1; open "http://127.0.0.1:8080") &
/usr/bin/ruby server.rb
