package com.omni.payment.dto;

import com.omni.payment.entity.Payment;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 用户可见的支付记录，不包含原始回调内容。 */
public class PaymentRecordResponse {
    private Long id;
    private Long orderId;
    private String paymentNo;
    private String paymentMethod;
    private String outTradeNo;
    private String tradeNo;
    private BigDecimal amount;
    private Integer status;
    private LocalDateTime payTime;

    public static PaymentRecordResponse from(Payment payment) {
        PaymentRecordResponse response = new PaymentRecordResponse();
        response.setId(payment.getId());
        response.setOrderId(payment.getOrderId());
        response.setPaymentNo(payment.getPaymentNo());
        response.setPaymentMethod(payment.getPaymentMethod());
        response.setOutTradeNo(payment.getOutTradeNo());
        response.setTradeNo(payment.getTradeNo());
        response.setAmount(payment.getAmount());
        response.setStatus(payment.getStatus());
        response.setPayTime(payment.getPayTime());
        return response;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public String getPaymentNo() { return paymentNo; }
    public void setPaymentNo(String paymentNo) { this.paymentNo = paymentNo; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
    public String getOutTradeNo() { return outTradeNo; }
    public void setOutTradeNo(String outTradeNo) { this.outTradeNo = outTradeNo; }
    public String getTradeNo() { return tradeNo; }
    public void setTradeNo(String tradeNo) { this.tradeNo = tradeNo; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public LocalDateTime getPayTime() { return payTime; }
    public void setPayTime(LocalDateTime payTime) { this.payTime = payTime; }
}
