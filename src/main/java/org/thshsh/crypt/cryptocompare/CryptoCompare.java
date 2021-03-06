package org.thshsh.crypt.cryptocompare;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.lang3.RandomUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.springframework.web.util.UriBuilder;
import org.springframework.web.util.UriBuilderFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class CryptoCompare {

	public static Logger LOGGER = LoggerFactory.getLogger(CryptoCompare.class);

	String cacheFile = "price-history.csv";
	//String cacheFileBackup = "price-cache-backup.csv";

	String apiKey;
	ThreadLocal<String> apiKeyThreadLocal = new ThreadLocal<String>();
	Map<String, Collection<CacheEntry>> currencyEntryCache = new HashMap<>();
	List<CacheEntry> cacheEntries = new ArrayList<>();
	Integer apiCount;
	Integer apiMax;

	ObjectMapper objectMapper = new ObjectMapper(); 

	static BigInteger HOUR = new BigInteger("3600");

	UriBuilderFactory uriBuilder = new DefaultUriBuilderFactory();
	RestTemplate template;
	String urlStart = "https://min-api.cryptocompare.com/data/";
	String mediaUrl = "https://www.cryptocompare.com/";


	public CryptoCompare(String key) {
		this(key,null);
	}

	public CryptoCompare(String key,String cacheFile) {

		template = new RestTemplate();

		this.cacheFile = cacheFile;
		this.apiKey = key;
		this.currencyEntryCache = new HashMap<>();
		this.apiCount = 0;
		this.apiMax = 0;

		LOGGER.info("CryptoCompare key: {}", key);

		List<CacheEntry> fromFile = loadCacheEntries(cacheFile);
		fromFile.forEach(this::addToCache);

		//rewrite this with proper times

		/*	for(String k : apiCache.keySet()) {
				CacheEntry entry = apiCache.get(k);
				String[] parts = entry.key.split("-");
				BigInteger time = new BigInteger(parts[1]);
				BigInteger downToHour = time.divide(HOUR).multiply(HOUR);
				LOGGER.info(key);
			}*/

		//apiCacheBackup = loadCache(cacheFileBackup);

		/*	try {
				File cf = new File(cacheFile);
				FileInputStream fis = new FileInputStream(cf);
				CSVParser parser = CSVFormat.DEFAULT.parse(new InputStreamReader(fis));

				for (final CSVRecord record : parser) {
					String k = record.get(0);
					BigDecimal v = new BigDecimal(record.get(1));
					apiCache.put(k, v);
				}

				parser.close();
			}
			catch (IOException e) {
				throw new IllegalStateException(e);
			}*/

		/*var loadedFile = d3.text("price-cache.csv"+"?"+randomQueryAttribute());
		loadedFile.then(function(text) {
			self.LOGGER.info("loaded file");
			d3.csvParseRows(text).map(function (row) {
				self.apiCache.set(row[0], new BigNum(row[1]));
			});
			self.LOGGER.info("done loading cache: {}",self.apiCache);
			d3.select("#api-cache-status").text("Cache Size: "+self.apiCache.size);
		});*/

	}


	protected CacheEntry mapNodeToEntry(String asset,JsonNode lastPoint) {
		//JsonNode lastPoint = points.get(points.size() - 1);

		BigInteger start = new BigInteger(lastPoint.get("time").toString());
		BigInteger end = start.add(HOUR);

		BigDecimal low = new BigDecimal(lastPoint.get("low").toString());
		BigDecimal high = new BigDecimal(lastPoint.get("high").toString());
		BigDecimal open = new BigDecimal(lastPoint.get("open").toString());
		BigDecimal close = new BigDecimal(lastPoint.get("close").toString());

		CacheEntry entry = new CacheEntry(asset, start, end, high, low, open, close);
		return entry;
	}

	//https://min-api.cryptocompare.com/data/v4/all/exchanges

	public ExchangesCurrencyPairs getExchangeCurrencyPairs(boolean topTier) {
		UriBuilder ub = getUri().pathSegment("v4","all","exchanges");
		if(topTier) ub = ub.queryParam("topTier", true);
		ExchangesCurrencyPairs exes = getForObject(ub, ExchangesCurrencyPairs.class);
		return exes;
	}

	protected UriBuilder getUri() {
		return uriBuilder.uriString(urlStart);
	}


	public Collection<Exchange> getExchanges() {
		Exchanges exes = getForObject(getUri().pathSegment("exchanges","general"), Exchanges.class);
		return exes.data.values();
	}

	public Collection<Coin> getCoins() {
		Coins exes = getForObject(getUri().pathSegment("all","coinlist"), Coins.class);
		return exes.data.values();
	}

	public InputStream getImage(Exchange e) {
		String imageUrl = e.getLogoUrl();
		return getImage(imageUrl);
	}

	public InputStream getImage(Coin c) {
		String imageUrl = c.getImageUrl();
		return getImage(imageUrl);
	}


	/**
	 * This doesn't actually use the API
	 * @param imageUrl
	 * @return
	 */
	public InputStream getImage(String imageUrl) {

		if(imageUrl != null) {
			String url = mediaUrl + imageUrl;
			try {
				URL u = new URL(url);
				InputStream is = u.openStream();
				return is;
			}
			catch (IOException e) {
				return null;
			}
		}
		return null;
	}

	//TODO need to round the time up to the nearest hour, since CC API is going to round DOWN to nearest hour
	//and will return the prior and next hours eg
	//12:12 returns 11-12 and 12-1
	//12 exactly will also return 11-12 and 12-1
	//11:59:59 returns 10-11 and 11-12
	/**
	 * eg if we request 8:15 CC returns 7-8 and 8-9
	 *
	 * EG
	 * 1513599139 = 1513594800 - 1513598400
	 * December 18, 2017 12:12:19 PM = December 18, 2017 11:00:00 AM - December 18, 2017 12:00:00 PM
	 * 					avg			high		low			open		close
	 * BTC-1513599139,	19062.95,	19212.68,	18945.1,	19144.54,	18949.48
	 *
	 *ETH,1512320400,1512324000,479.14,475.72,476.03,477.47
	 *DAI,1613034000,1613037600,1.002,1,1.001,1.001
	 *
	 * @param asset
	 * @param time
	 * @return
	 */
	@Deprecated
	public BigDecimal getFiatPrice(String asset, BigInteger time) {

		//if(asset == FIAT_ASSET) return new BigNum(1);
		LOGGER.info("getFiatPrice {}, {}", asset, time);

		//String key = asset + "-" + time;

		CacheEntry found = findEntry(asset, time);

		LOGGER.info("found {}", found);

		if (found == null) {
			this.apiCount++;

			String url = getUri().path("v2/histohour")
			.queryParam("fsym",asset)
			.queryParam("tsym","USD")
			.queryParam("toTs",time)
			.queryParam("limit","1")
			.build().toString();
			//String url = getUrl("v2/histohour");
			 //url = url+"?fsym=" + asset + "&tsym=USD&toTs=" + time + "&limit=1&api_key=" + this.apiKey;
			
			LOGGER.info("URL: {}", url);

			if (this.apiCount > this.apiMax) {
				LOGGER.error("api count reached max looking for {} , {}", new Object[] { asset, time });
				throw new IllegalStateException("api count reached max");
			}

			try {
				JsonNode node = executeUrl(url);

				JsonNode points = node.get("Data").get("Data");

				CacheEntry entry = null;
				for(int i=0;i<points.size();i++) {
					JsonNode lastPoint = points.get(i);
					entry = mapNodeToEntry(asset, lastPoint);
					addToCache(entry);
					appendToNew(entry);
				}

				LOGGER.info("{}", entry);

				found = entry;

			} catch (IOException e) {
				LOGGER.warn("",e);
			}

		}

		return found.average;

	}

	//https://min-api.cryptocompare.com/data/pricemulti?fsyms=BTC,ETH&tsyms=USD,EUR

	public CurrentPricesResponse getCurrentPrice(String to, String... assets) {
		return this.getCurrentPrice(to,Arrays.asList(assets));
	}
	
	public CurrentPricesResponse getCurrentPrice(String to,Collection<String> assets) {
		String list = assets.stream().collect(Collectors.joining(","));
		to = to.toUpperCase();
		
		UriBuilder uri = getUri().path("pricemulti")
				.queryParam("fsyms",list)
				.queryParam("tsyms",to.toUpperCase())
				;

			CurrentPricesResponse exes = getForObject(uri, CurrentPricesResponse.class);
			exes.setToCurrency(to);
			return exes;

	}
	
	protected <T extends Response> T getForObject(UriBuilder ub, Class<T> classs){
		
		String apiKey = apiKeyThreadLocal.get();
		if(apiKey == null) apiKey = this.apiKey;
		URI uri = ub.queryParam("api_key", apiKey).build();
		T t = template.getForObject(uri, classs);
		if("Error".equals(t.getResponse())) {
			throw new CryptoCompareException(t.getMessage());
		}
		return t;
	}

	@Deprecated
	protected JsonNode executeUrl(String url) throws IOException {

		if (this.apiCount > this.apiMax) {
			LOGGER.error("api count reached max calling",url);
			throw new IllegalStateException("api count reached max");
		}

		LOGGER.info("read url: {}",url);
		URL u = new URL(url);
		HttpURLConnection connection = (HttpURLConnection) u.openConnection();
		LOGGER.info("response: {} / {}",connection.getResponseCode(),connection.getResponseMessage());
		
		try (InputStream is = connection.getInputStream();) {

			
			JsonNode node = objectMapper.readTree(is);
			
			
			if(node.has("Response") && "Error".equals(node.get("Response").asText())) {
				String message = node.get("Message").asText();
				
			}
			
			LOGGER.info("node: {}",node);
			return node;
			
		}




	}

	@Deprecated
	public BigDecimal getFiatPrice(String asset, long time) {
		return getFiatPrice(asset, BigInteger.valueOf(time));
	}

	public CacheEntry findEntry(String asset, BigInteger time) {
		//find an entry that is between these

		Collection<CacheEntry> forAsset = getAssetCache(asset);
		for (CacheEntry entry : forAsset) {
			if (entry.includesTime(time)) {
				LOGGER.info("Entry {} includes time {}",entry,time);
				return entry;
			}
		}

		return null;
	}

	protected void addToCache(CacheEntry entry) {
		cacheEntries.add(entry);
		getAssetCache(entry.asset).add(entry);
	}

	public Collection<CacheEntry> getAssetCache(String asset) {
		if (!currencyEntryCache.containsKey(asset))
			currencyEntryCache.put(asset, new ArrayList<>());
		return currencyEntryCache.get(asset);
	}

	protected void appendToNew(CacheEntry entry) {

		try (FileWriter fw = new FileWriter(new File(cacheFile), true); BufferedWriter bw = new BufferedWriter(fw)) {
			appendToNew(entry, bw);

			bw.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	protected void appendToNew(CacheEntry entry, BufferedWriter bw) throws IOException {

		bw.newLine();
		bw.write(entry.asset);
		bw.write("," + entry.startTime);
		bw.write("," + entry.endTime);
		//bw.write(","+entry.average);
		bw.write("," + entry.high);
		bw.write("," + entry.low);
		bw.write("," + entry.open);
		bw.write("," + entry.close);

	}

	public String randomQueryAttribute() {

		return ((int) RandomUtils.nextFloat() * 1000000) + "";

	}

	
	
	
	

	public ThreadLocal<String> getApiKeyThreadLocal() {
		return apiKeyThreadLocal;
	}

	public String getApiKey() {
		return apiKey;
	}

	public void setApiKey(String apiKey) {
		this.apiKey = apiKey;
	}

	public static List<CacheEntry> loadCacheEntries(String cacheFile) {

		List<CacheEntry> apiCache = new ArrayList<>();

		if(cacheFile != null) {

			try {
				File cf = new File(cacheFile);
				FileInputStream fis = new FileInputStream(cf);
				CSVParser parser = CSVFormat.DEFAULT.parse(new InputStreamReader(fis));

				for (final CSVRecord record : parser) {
					CacheEntry ce = new CacheEntry(record);
					apiCache.add(ce);
				}

				parser.close();


			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
		}

		return apiCache;
	}

}