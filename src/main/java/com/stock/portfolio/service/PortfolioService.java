package com.stock.portfolio.service;

import com.stock.portfolio.model.TradeRequest;
import com.stock.portfolio.model.UserPortfolio;
import com.stock.portfolio.entity.UserEntity;
import com.stock.portfolio.entity.HoldingEntity;
import com.stock.portfolio.repository.UserRepository;
import com.stock.portfolio.repository.HoldingRepository;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class PortfolioService {

    @Autowired
    private UserRepository userRepo;

    @Autowired
    private HoldingRepository holdRepo;

    @Value("${ALPHA_API_KEY}")
    private String API_KEY;   // 🔐 FROM ENVIRONMENT VARIABLE

    private final RestTemplate rest = new RestTemplate();

    // Stock → AlphaVantage symbol mapping
    private final Map<String, String> stockSymbols = Map.of(
            "Tata Motors", "TATAMOTORS.NS",
            "Axis", "AXISBANK.NS",
            "Britania", "BRITANNIA.NS",
            "ICICI", "ICICIBANK.NS",
            "HCL Tech", "HCLTECH.NS",
            "Jio Financial Services", "JIOFIN.NS",
            "Bharat Electronics", "BEL.NS",
            "Nestle India", "NESTLEIND.NS"
    );

    // Fallback prices (used when API fails)
    private final Map<String, Double> fallbackPrices = Map.of(
            "Tata Motors", 709.60,
            "Axis", 1175.60,
            "Britania", 5549.50,
            "ICICI", 1429.00,
            "HCL Tech", 1641.50,
            "Jio Financial Services", 283.55,
            "Bharat Electronics", 384.80,
            "Nestle India", 2398.00
    );

    private static final String API_URL =
            "https://www.alphavantage.co/query?function=GLOBAL_QUOTE&symbol=%s&apikey=%s";

    // ----------------------------------------
    // 🔹 FETCH LIVE PRICE
    // ----------------------------------------
    public double fetchLivePrice(String stock) {
        try {
            String symbol = stockSymbols.get(stock);
            if (symbol == null) return fallbackPrices.get(stock);

            String url = String.format(API_URL, symbol, API_KEY);
            Map response = rest.getForObject(url, Map.class);

            Map quote = (Map) response.get("Global Quote");
            if (quote != null && quote.get("05. price") != null) {
                return Double.parseDouble((String) quote.get("05. price"));
            }

        } catch (Exception e) {
            System.out.println("⚠ API error for " + stock + " → using fallback price");
        }

        return fallbackPrices.get(stock);
    }

    // ----------------------------------------
    // 🔹 GET PORTFOLIO (primary endpoint)
    // ----------------------------------------
    public UserPortfolio getPortfolio(String username) {

        UserEntity user = userRepo.findById(username)
                .orElseGet(() -> userRepo.save(new UserEntity(username)));

        List<HoldingEntity> holdings = holdRepo.findByUsername(username);

        UserPortfolio portfolio = new UserPortfolio(username);
        portfolio.setBalance(user.getBalance());

        holdings.forEach(h ->
                portfolio.getHoldings().put(h.getStock(), h.getQuantity())
        );

        // Fetch live prices for all stocks
        Map<String, Double> livePrices = stockSymbols.keySet().stream()
                .collect(Collectors.toMap(
                        stock -> stock,
                        this::fetchLivePrice
                ));

        portfolio.setPrices(livePrices);

        return portfolio;
    }

    // ----------------------------------------
    // 🔹 BUY STOCK
    // ----------------------------------------
    public UserPortfolio buyStock(String username, TradeRequest trade) {

        if (trade.getQuantity() <= 0)
            throw new RuntimeException("Please enter valid quantity");

        if (!stockSymbols.containsKey(trade.getStock()))
            throw new RuntimeException("Stock not available for trading");

        UserPortfolio portfolio = getPortfolio(username);

        double price = fetchLivePrice(trade.getStock());
        double cost = trade.getQuantity() * price;

        if (cost > portfolio.getBalance())
            throw new RuntimeException("Insufficient balance");

        double newBalance = portfolio.getBalance() - cost;
        portfolio.setBalance(newBalance);

        portfolio.getHoldings().merge(trade.getStock(), trade.getQuantity(), Integer::sum);

        userRepo.save(new UserEntity(username, newBalance));

        HoldingEntity h = holdRepo.findByUsernameAndStock(username, trade.getStock())
                .orElse(new HoldingEntity(username, trade.getStock(), 0));

        h.setQuantity(portfolio.getHoldings().get(trade.getStock()));
        holdRepo.save(h);

        return getPortfolio(username);
    }

    // ----------------------------------------
    // 🔹 SELL STOCK
    // ----------------------------------------
    public UserPortfolio sellStock(String username, TradeRequest trade) {

        if (trade.getQuantity() <= 0)
            throw new RuntimeException("Please enter valid quantity");

        UserPortfolio portfolio = getPortfolio(username);

        if (!portfolio.getHoldings().containsKey(trade.getStock()))
            throw new RuntimeException("You do not own this stock");

        int owned = portfolio.getHoldings().get(trade.getStock());

        if (owned < trade.getQuantity())
            throw new RuntimeException("Not enough quantity to sell");

        double price = fetchLivePrice(trade.getStock());
        double revenue = price * trade.getQuantity();

        double newBalance = portfolio.getBalance() + revenue;
        portfolio.setBalance(newBalance);

        int remaining = owned - trade.getQuantity();

        if (remaining == 0)
            portfolio.getHoldings().remove(trade.getStock());
        else
            portfolio.getHoldings().put(trade.getStock(), remaining);

        userRepo.save(new UserEntity(username, newBalance));

        HoldingEntity h = holdRepo.findByUsernameAndStock(username, trade.getStock())
                .orElseThrow(() -> new RuntimeException("DB holding missing"));

        if (remaining == 0)
            holdRepo.delete(h);
        else {
            h.setQuantity(remaining);
            holdRepo.save(h);
        }

        return getPortfolio(username);
    }
}
