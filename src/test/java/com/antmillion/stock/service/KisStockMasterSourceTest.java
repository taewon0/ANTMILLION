package com.antmillion.stock.service;

import com.antmillion.stock.dto.StockDTO;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public class KisStockMasterSourceTest {
    @Test
    public void parsesBothMarketLayoutsAndLineEndingsWithoutCuttingNames() throws Exception {
        for (String market : Arrays.asList("KOSPI", "KOSDAQ")) {
            for (String newline : Arrays.asList("\n", "\r\n")) {
                String row = record("TEST01", "합성종목이름", market, "ST") + newline;
                List<StockDTO> stocks = source(archive(market.toLowerCase() + "_code.mst", row)).loadStocks(market);
                assertEquals(1, stocks.size());
                assertEquals("TEST01", stocks.get(0).getStockCode());
                assertEquals("합성종목이름", stocks.get(0).getStockName());
            }
        }
    }

    @Test
    public void rejectsEmptyMalformedAndUnsupportedRecords() throws Exception {
        for (String row : Arrays.asList("", "broken\n", record("TEST01", "", "KOSPI", "ST"),
                record("bad!", "잘못된코드", "KOSPI", "ST"))) {
            byte[] archive = archive("kospi_code.mst", row);
            assertThrows(IOException.class, () -> source(archive).loadStocks("KOSPI"));
        }
    }

    @Test
    public void rejectsWrongZipEntryAndTruncatedArchive() throws Exception {
        assertThrows(IOException.class, () -> source(archive("other.mst", "anything")).loadStocks("KOSPI"));
        byte[] zip = archive("kospi_code.mst", record("TEST01", "합성종목", "KOSPI", "ST"));
        assertThrows(IOException.class, () -> source(Arrays.copyOf(zip, 45)).loadStocks("KOSPI"));
    }

    @Test
    public void filtersElwWithoutTreatingAnEmptyUniverseAsSuccess() throws Exception {
        String row = record("TEST01", "제외종목", "KOSPI", "EW");
        assertThrows(IOException.class, () -> source(archive("kospi_code.mst", row)).loadStocks("KOSPI"));
    }

    private static KisStockMasterSource source(byte[] zip) {
        return new KisStockMasterSource() {
            @Override
            protected InputStream openArchive(String market) {
                return new ByteArrayInputStream(zip);
            }
        };
    }

    private static String record(String code, String name, String market, String group) {
        int width = "KOSPI".equals(market) ? 227 : 221;
        return String.format("%-9s%-12s%-30s", code, "SYNTHETIC", name)
                + group + " ".repeat(width - group.length());
    }

    private static byte[] archive(String filename, String data) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(filename));
            zip.write(data.getBytes(Charset.forName("CP949")));
            zip.closeEntry();
        }
        return output.toByteArray();
    }
}
