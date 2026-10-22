package com.antmillion.marketdata;

import com.antmillion.marketdata.provider.CurrentPriceProvider;
import com.antmillion.marketdata.provider.InvestorFlowProvider;
import com.antmillion.marketdata.provider.MarketDataProviderRegistration;
import com.antmillion.marketdata.provider.MarketDataProviderRegistry;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/** 등록·선택 정책만 검증하며 외부 설정이나 서비스를 로드하지 않습니다. */
public class MarketDataProviderRegistryTest {
    private final InvestorFlowProvider kis = code -> null;
    private final InvestorFlowProvider alternate = code -> null;

    @Test
    public void selectedIdChoosesProviderRegardlessOfRegistrationOrder() {
        MarketDataProviderRegistration first = registration("kis", kis);
        MarketDataProviderRegistration second = registration("alternate", alternate);
        for (List<MarketDataProviderRegistration> order : List.of(List.of(first, second), List.of(second, first))) {
            MarketDataProviderRegistry registry = new MarketDataProviderRegistry("alternate", order);
            assertEquals("alternate", registry.getSelectedId());
            assertSame(alternate, registry.getProvider(InvestorFlowProvider.class));
            assertEquals(second.getSupportedContracts(), registry.getSupportedContracts());
        }
    }

    @Test
    public void missingBlankAndUnknownIdsNeverFallBack() {
        for (String id : new String[]{null, "", " ", "KIS", "kiwoom"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new MarketDataProviderRegistry(id, List.of(registration("kis", kis))));
        }
        assertThrows(IllegalArgumentException.class, () -> new MarketDataProviderRegistry("kis", List.of()));
    }

    @Test
    public void duplicateIdsFailEvenWhenAnotherProviderWasSelected() {
        assertThrows(IllegalArgumentException.class, () -> new MarketDataProviderRegistry("alternate",
                List.of(registration("kis", kis), registration("kis", alternate),
                        registration("alternate", alternate))));
    }

    @Test
    public void unsupportedContractNeverBorrowsFromAnotherProvider() {
        MarketDataProviderRegistry registry = new MarketDataProviderRegistry("alternate",
                List.of(registration("kis", kis), MarketDataProviderRegistration.builder("alternate").build()));
        assertTrue(registry.getSupportedContracts().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> registry.getProvider(InvestorFlowProvider.class));
        assertThrows(UnsupportedOperationException.class, () -> registry.getProvider(CurrentPriceProvider.class));
    }

    @Test
    public void publishedRegistrationAndSupportedContractsCannotBeChanged() {
        MarketDataProviderRegistration.Builder builder = MarketDataProviderRegistration.builder("kis");
        MarketDataProviderRegistration empty = builder.build();
        builder.add(InvestorFlowProvider.class, kis);
        MarketDataProviderRegistration complete = builder.build();
        assertTrue(empty.getSupportedContracts().isEmpty());
        assertSame(kis, complete.getProvider(InvestorFlowProvider.class));
        assertThrows(UnsupportedOperationException.class, () -> complete.getSupportedContracts().clear());
    }

    @Test
    public void invalidIdsDuplicateContractsAndUnrelatedTypesAreRejected() {
        for (String id : new String[]{null, "", "KIS", "kis test", " kis"}) {
            assertThrows(IllegalArgumentException.class, () -> MarketDataProviderRegistration.builder(id));
        }
        MarketDataProviderRegistration.Builder builder = MarketDataProviderRegistration.builder("kis")
                .add(InvestorFlowProvider.class, kis);
        assertThrows(IllegalArgumentException.class, () -> builder.add(InvestorFlowProvider.class, alternate));
        assertThrows(IllegalArgumentException.class, () -> builder.add(String.class, "unrelated"));
        assertThrows(NullPointerException.class, () -> builder.add(CurrentPriceProvider.class, null));
    }

    private MarketDataProviderRegistration registration(String id, InvestorFlowProvider provider) {
        return MarketDataProviderRegistration.builder(id).add(InvestorFlowProvider.class, provider).build();
    }
}
