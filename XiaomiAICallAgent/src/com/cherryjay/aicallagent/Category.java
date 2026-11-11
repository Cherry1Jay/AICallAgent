package com.cherryjay.aicallagent;

/**
 * 来电类型判定。
 *
 * 小米系统自带黄页标记（miui.yellowpage.YellowPageUtils#getPhoneInfo(ctx, number, true).getTag()），
 * 能给出"外卖""快递""骚扰电话""广告推销""房产中介"这类标签。
 * 我们把它归一成 6 类，每类可单独决定交给大模型还是走原生。
 */
public final class Category {

    public static final String TAKEOUT = "takeout";
    public static final String DELIVERY = "delivery";
    public static final String SPAM = "spam";
    public static final String SERVICE = "service";
    public static final String CONTACT = "contact";
    public static final String UNKNOWN = "unknown";

    private static final String[] TAKEOUT_KEYS = {
            "外卖", "送餐", "美团", "饿了么", "配送", "跑腿", "闪送", "买菜", "超市", "奶茶", "咖啡",
            "餐厅", "饭店", "food", "delivery"
    };

    private static final String[] DELIVERY_KEYS = {
            "快递", "物流", "驿站", "顺丰", "圆通", "中通", "申通", "韵达", "极兔", "京东物流",
            "邮政", "菜鸟", "德邦", "速运", "快件", "包裹", "express", "courier", "parcel"
    };

    private static final String[] SPAM_KEYS = {
            "骚扰", "推销", "广告", "中介", "保险", "贷款", "理财", "培训", "招聘", "诈骗",
            "催收", "荐股", "房产", "装修", "教育", "营销", "外呼", "spam", "telemarket",
            "marketing", "sales", "promotion"
    };

    private static final String[] SERVICE_KEYS = {
            "客服", "官方", "银行", "航空", "运营商", "移动", "联通", "电信", "营业厅",
            "政务", "医院", "学校", "售后", "service", "support"
    };

    private Category() {
    }

    public static String classify(String yellowPageTag, boolean isContact, String number) {
        String tag = yellowPageTag == null ? "" : yellowPageTag.toLowerCase();
        if (!tag.isEmpty()) {
            if (hits(tag, TAKEOUT_KEYS)) {
                return TAKEOUT;
            }
            if (hits(tag, DELIVERY_KEYS)) {
                return DELIVERY;
            }
            if (hits(tag, SPAM_KEYS)) {
                return SPAM;
            }
            if (hits(tag, SERVICE_KEYS)) {
                return SERVICE;
            }
        }
        if (isContact) {
            return CONTACT;
        }
        if (looksLikeServiceNumber(number)) {
            return SERVICE;
        }
        return UNKNOWN;
    }

    public static String label(String category) {
        switch (category) {
            case TAKEOUT:
                return "外卖送餐";
            case DELIVERY:
                return "快递物流";
            case SPAM:
                return "广告推销";
            case SERVICE:
                return "客服官方";
            case CONTACT:
                return "通讯录联系人";
            default:
                return "陌生号码";
        }
    }

    private static boolean hits(String tag, String[] keys) {
        for (String k : keys) {
            if (tag.contains(k.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /** 95xxx / 400 / 10086 这类官方短号 */
    private static boolean looksLikeServiceNumber(String number) {
        if (number == null) {
            return false;
        }
        String n = number.replaceAll("[^0-9+]", "");
        if (n.length() >= 5 && n.length() <= 6) {
            return true;
        }
        if (n.startsWith("400") || n.startsWith("800")) {
            return true;
        }
        return n.startsWith("95") && n.length() <= 8;
    }
}
