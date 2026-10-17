package com.antmillion.stock.service;

import com.antmillion.stock.dto.StockDTO;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class KisStockMasterSource {
    private static final int MAX_MASTER_BYTES = 20 * 1024 * 1024;

    public List<StockDTO> loadStocks(String market) throws IOException {
        int tailWidth;
        if ("KOSPI".equals(market)) {
            tailWidth = 227;
        } else if ("KOSDAQ".equals(market)) {
            tailWidth = 221;
        } else {
            throw new IllegalArgumentException("지원하지 않는 시장입니다.");
        }
        String filename = market.toLowerCase(java.util.Locale.ROOT) + "_code.mst";
        try (InputStream input = openArchive(market); ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry = zip.getNextEntry();
            if (entry == null || entry.isDirectory() || !filename.equals(entry.getName())) {
                throw new IOException("종목 마스터 ZIP 구성이 올바르지 않습니다.");
            }
            byte[] bytes = zip.readNBytes(MAX_MASTER_BYTES + 1);
            if (bytes.length > MAX_MASTER_BYTES || zip.getNextEntry() != null) {
                throw new IOException("종목 마스터 크기 또는 ZIP 구성이 올바르지 않습니다.");
            }
            List<StockDTO> result = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes),
                    Charset.forName("CP949").newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.length() <= 21 + tailWidth || line.length() > 2048) {
                        throw new IOException("잘못된 종목 마스터 레코드입니다.");
                    }
                    int tailStart = line.length() - tailWidth;
                    String code = line.substring(0, 9).trim();
                    String name = line.substring(21, tailStart).trim();
                    String group = line.substring(tailStart, tailStart + 2).trim();
                    if ("EW".equals(group)) {
                        continue;
                    }
                    if (!code.matches("[A-Za-z0-9]{6,9}") || name.isEmpty() || name.length() > 100) {
                        throw new IOException("종목 코드 또는 이름이 올바르지 않습니다.");
                    }
                    // The master also contains longer bond/ETN/rights codes outside our six-character universe.
                    if (code.length() != 6) {
                        continue;
                    }
                    result.add(StockDTO.builder().stockCode(code).stockName(name).build());
                }
            }
            if (result.isEmpty()) {
                throw new IOException("종목 목록이 비어 있습니다.");
            }
            return result;
        }
    }

    protected InputStream openArchive(String market) throws IOException {
        String filename = market.toLowerCase(java.util.Locale.ROOT) + "_code.mst.zip";
        URLConnection connection = new URL("https://new.real.download.dws.co.kr/common/master/" + filename)
                .openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        return connection.getInputStream();
    }
}
