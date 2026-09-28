package com.guicedee.activitymaster.wallet;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Transport DTOs use decimal strings to preserve exact accounting precision. */
public final class WalletModels {
    private WalletModels() { }
    public enum Action { TRANSFER, DEPOSIT, WITHDRAWAL }
    public record Create(UUID operationKey) {
        public Create { Objects.requireNonNull(operationKey, "operationKey required"); }
    }
    public record Movement(UUID operationKey, UUID sourceId, UUID destinationId, String amount, String unit) {
        public Movement {
            Objects.requireNonNull(operationKey, "operationKey required");
            Objects.requireNonNull(sourceId, "sourceId required");
            Objects.requireNonNull(destinationId, "destinationId required");
            if (sourceId.equals(destinationId)) throw new IllegalArgumentException("Distinct arrangements required");
            if (amount == null || !amount.matches("[0-9]{1,30}(\\.[0-9]{1,8})?") || new BigDecimal(amount).signum() <= 0)
                throw new IllegalArgumentException("Positive decimal amount with up to 8 fractional digits required");
            requireUnit(unit);
        }
        public BigDecimal decimalAmount() { return new BigDecimal(amount); }
    }
    public record Wallet(UUID arrangementId, UUID involvedPartyId) { }
    public record Balance(UUID arrangementId, String unit, String amount) { }
    public record Line(int number, UUID arrangementId, UUID transactionTypeId, int direction, String amount, String unit) { }
    public record Receipt(UUID eventId, UUID operationKey, List<Line> lines) {
        public Receipt { lines = List.copyOf(lines); }
    }
    public record HistoryLine(UUID transactionId, UUID eventId, int lineNumber, int direction, String amount, String unit) { }
    public static void requireUnit(String unit) {
        if (unit == null || !unit.matches("[A-Z][A-Z0-9_]{0,15}")) throw new IllegalArgumentException("Unit required");
    }
}
