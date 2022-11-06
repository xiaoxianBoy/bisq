/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.core.dao.burningman;

import bisq.core.dao.state.DaoStateListener;
import bisq.core.dao.state.DaoStateService;
import bisq.core.dao.state.model.blockchain.Block;

import bisq.common.util.Tuple2;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.google.common.annotations.VisibleForTesting;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Singleton
public class DelayedPayoutTxReceiverService implements DaoStateListener {

    // One part of the limit for the min. amount to be included in the DPT outputs.
    // The miner fee rate multiplied by 2 times the output size is the other factor.
    // The higher one of both is used. 1000 sat is about 2 USD @ 20k price.
    private static final long DPT_MIN_OUTPUT_AMOUNT = 1000;

    // If at DPT there is some leftover amount due to capping of some receivers (burn share is
    // max. ISSUANCE_BOOST_FACTOR times the issuance share) we send it to legacy BM if it is larger
    // than DPT_MIN_REMAINDER_TO_LEGACY_BM, otherwise we spend it as miner fee.
    // 50000 sat is about 10 USD @ 20k price. We use a rather high value as we want to avoid that the legacy BM
    // gets still payouts.
    private static final long DPT_MIN_REMAINDER_TO_LEGACY_BM = 50000;

    // Min. fee rate for DPT. If fee rate used at take offer time was higher we use that.
    // We prefer a rather high fee rate to not risk that the DPT gets stuck if required fee rate would
    // spike when opening arbitration.
    private static final long DPT_MIN_TX_FEE_RATE = 10;


    private final DaoStateService daoStateService;
    private final BurningManService burningManService;
    private int currentChainHeight;

    @Inject
    public DelayedPayoutTxReceiverService(DaoStateService daoStateService, BurningManService burningManService) {
        this.daoStateService = daoStateService;
        this.burningManService = burningManService;

        daoStateService.addDaoStateListener(this);

        //todo
        burningManService.setDelayedPayoutTxReceiverService(this);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // DaoStateListener
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onParseBlockCompleteAfterBatchProcessing(Block block) {
        currentChainHeight = block.getHeight();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////


    // We use a snapshot blockHeight to avoid failed trades in case maker and taker have different block heights.
    // The selection is deterministic based on DAO data.
    // The block height is the last mod(10) height from the range of the last 10-20 blocks (139 -> 120; 140 -> 130, 141 -> 130).
    // We do not have the latest dao state by that but can ensure maker and taker have the same block.
    public int getBurningManSelectionHeight() {
        return getSnapshotHeight(daoStateService.getGenesisBlockHeight(), currentChainHeight, 10);
    }

    public List<Tuple2<Long, String>> getDelayedPayoutTxReceivers(int burningManSelectionHeight,
                                                                  long inputAmount,
                                                                  long tradeTxFee) {
        Collection<BurningManCandidate> burningManCandidates = burningManService.getBurningManCandidatesByName(burningManSelectionHeight).values();
        if (burningManCandidates.isEmpty()) {
            // If there are no compensation requests (e.g. at dev testing) we fall back to the legacy BM
            return List.of(new Tuple2<>(inputAmount, BurningManUtil.getLegacyBurningManAddress(daoStateService, burningManSelectionHeight)));
        }

        // We need to use the same txFeePerVbyte value for both traders.
        // We use the tradeTxFee value which is calculated from the average of taker fee tx size and deposit tx size.
        // Otherwise, we would need to sync the fee rate of both traders.
        // In case of very large taker fee tx we would get a too high fee, but as fee rate is anyway rather
        // arbitrary and volatile we are on the safer side. The delayed payout tx is published long after the
        // take offer event and the recommended fee at that moment might be very different to actual
        // recommended fee. To avoid that the delayed payout tx would get stuck due too low fees we use a
        // min. fee rate of 10 sat/vByte.

        // Deposit tx has a clearly defined structure, so we know the size. It is only one optional output if range amount offer was taken.
        // Smallest tx size is 246. With additional change output we add 32. To be safe we use the largest expected size.
        double txSize = 278;
        long txFeePerVbyte = Math.max(DPT_MIN_TX_FEE_RATE, Math.round(tradeTxFee / txSize));
        long spendableAmount = getSpendableAmount(burningManCandidates.size(), inputAmount, txFeePerVbyte);
        // We only use outputs > 1000 sat or at least 2 times the cost for the output (32 bytes).
        // If we remove outputs it will be spent as miner fee.
        long minOutputAmount = Math.max(DPT_MIN_OUTPUT_AMOUNT, txFeePerVbyte * 32 * 2);

        List<Tuple2<Long, String>> receivers = burningManCandidates.stream()
                .filter(candidate -> candidate.getMostRecentAddress().isPresent())
                .map(candidates -> new Tuple2<>(Math.round(candidates.getEffectiveBurnOutputShare() * spendableAmount),
                        candidates.getMostRecentAddress().get()))
                .filter(tuple -> tuple.first >= minOutputAmount)
                .sorted(Comparator.<Tuple2<Long, String>, Long>comparing(tuple -> tuple.first)
                        .thenComparing(tuple -> tuple.second))
                .collect(Collectors.toList());
        long totalOutputValue = receivers.stream().mapToLong(e -> e.first).sum();
        if (totalOutputValue < spendableAmount) {
            long available = spendableAmount - totalOutputValue;
            // If the available is larger than DPT_MIN_REMAINDER_TO_LEGACY_BM we send it to legacy BM
            // Otherwise we use it as miner fee
            if (available > DPT_MIN_REMAINDER_TO_LEGACY_BM) {
                receivers.add(new Tuple2<>(available, BurningManUtil.getLegacyBurningManAddress(daoStateService, burningManSelectionHeight)));
            }
        }
        return receivers;
    }

    private static long getSpendableAmount(int numOutputs, long inputAmount, long txFeePerVbyte) {
        // Output size: 32 bytes
        // Tx size without outputs: 51 bytes
        int txSize = 51 + numOutputs * 32;
        long minerFee = txFeePerVbyte * txSize;
        return inputAmount - minerFee;
    }

    // Borrowed from DaoStateSnapshotService. We prefer to not reuse to avoid dependency to an unrelated domain.
    @VisibleForTesting
    static int getSnapshotHeight(int genesisHeight, int height, int grid) {
        return Math.round(Math.max(genesisHeight + 3 * grid, height) / grid) * grid - grid;
    }
}
