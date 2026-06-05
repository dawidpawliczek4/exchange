package com.dawidpawliczek.engine.domain;


public final class Order {

    private final long id;
    private final long userId;
    private final Side side;
    private final long price;
    private final boolean market;
    private long quantity;


    public Order(long id, long userId, Side side, long price, boolean market, long quantity) {
        this.id = id;
        this.userId = userId;
        this.side = side;
        this.price = price;
        this.market = market;
        this.quantity = quantity;
    }

    public long id()        { return id; }
    public long userId()    { return userId; }
    public Side side()      { return side; }
    public long price()     { return price; }
    public long quantity()  { return quantity; }
    public boolean isMarket() { return market; }

    public void reduce(long quantity) {
        this.quantity -= quantity;
    }
}
