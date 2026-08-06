package com.wendy.paygateway.reconcile.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wendy.paygateway.channel.adapter.ChannelAdapterFactory;
import com.wendy.paygateway.channel.entity.PayChannelConfig;
import com.wendy.paygateway.channel.service.PayChannelConfigService;
import com.wendy.paygateway.common.config.PayProperties;
import com.wendy.paygateway.common.enums.ChannelType;
import com.wendy.paygateway.common.exception.BizException;
import com.wendy.paygateway.common.exception.ErrorCode;
import com.wendy.paygateway.common.util.MoneyUtil;
import com.wendy.paygateway.reconcile.entity.ChannelBill;
import com.wendy.paygateway.reconcile.mapper.ChannelBillMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Downloads, archives and parses channel bill files.
 *
 * <p>It only ever gets the raw bill through {@code PayChannelAdapter#downloadBill}. It knows about
 * no concrete channel, and certainly not about the sandbox simulator — the only coupling between
 * the reconciliation domain and the channel implementations is that one interface, so switching to
 * the real Alipay/WeChat SDKs changes no reconciliation code at all.
 *
 * <p>The downloaded text is archived to disk ({@code pay.reconcile.bill-dir}) before being parsed
 * into the database. Archiving matters in a real project: when a reconciliation result is disputed,
 * you need to be able to pull up the exact file the channel gave you that day.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillDownloadService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ChannelAdapterFactory channelAdapterFactory;
    private final PayChannelConfigService payChannelConfigService;
    private final ChannelBillMapper channelBillMapper;
    private final PayProperties payProperties;

    /** Download, archive and parse into the database; returns the number of rows stored. */
    public int downloadAndParse(LocalDate billDate, ChannelType channelType) {
        PayChannelConfig config = payChannelConfigService.get(channelType);
        if (config == null) {
            throw BizException.of(ErrorCode.CHANNEL_NOT_FOUND, String.valueOf(channelType));
        }

        String content = channelAdapterFactory.get(channelType).downloadBill(billDate, config);
        if (content == null) {
            log.info("[Bill file] {} publishes no bill, skipping", channelType);
            return 0;
        }

        Path file = archive(billDate, channelType, content);
        List<ChannelBill> bills = parse(content, billDate, channelType);

        // Re-running reconciliation must be idempotent: clear this day's rows for this channel first
        channelBillMapper.delete(Wrappers.<ChannelBill>lambdaQuery()
                .eq(ChannelBill::getBillDate, billDate.format(DATE_FMT))
                .eq(ChannelBill::getChannelType, channelType));
        bills.forEach(channelBillMapper::insert);

        log.info("[Bill file] {} {} archived as {}, {} row(s) parsed into the database",
                billDate, channelType, file.getFileName(), bills.size());
        return bills.size();
    }

    /** Archive the raw bill to disk so it can be produced if the result is ever disputed. */
    private Path archive(LocalDate billDate, ChannelType channelType, String content) {
        try {
            Path dir = Paths.get(payProperties.getReconcile().getBillDir());
            Files.createDirectories(dir);
            Path file = dir.resolve(channelType.name().toLowerCase() + "_" + billDate.format(DATE_FMT) + ".csv");
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw BizException.of(ErrorCode.RECONCILE_FAIL, "failed to archive the bill file: " + e.getMessage());
        }
    }

    private List<ChannelBill> parse(String content, LocalDate billDate, ChannelType channelType) {
        List<ChannelBill> bills = new ArrayList<>();
        String[] lines = content.split("\\R");
        // Row 0 is the header
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line == null || line.isBlank()) {
                continue;
            }
            String[] cols = line.split(",", -1);
            if (cols.length < 6) {
                log.warn("[Bill file] skipping malformed row: {}", line);
                continue;
            }
            ChannelBill bill = new ChannelBill();
            bill.setBillDate(billDate.format(DATE_FMT));
            bill.setChannelType(channelType);
            bill.setOutTradeNo(cols[0]);
            bill.setChannelTradeNo(cols[1]);
            bill.setAmount(MoneyUtil.of(cols[2]));
            bill.setFee(MoneyUtil.of(cols[3]));
            bill.setTradeStatus(cols[4]);
            bill.setTradeTime(LocalDateTime.parse(cols[5], TIME_FMT));
            bills.add(bill);
        }
        return bills;
    }

    public List<ChannelBill> listBills(LocalDate billDate, ChannelType channelType) {
        return channelBillMapper.selectList(Wrappers.<ChannelBill>lambdaQuery()
                .eq(ChannelBill::getBillDate, billDate.format(DATE_FMT))
                .eq(ChannelBill::getChannelType, channelType));
    }
}
