package com.aitravel;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

public record TripInput(String startCity, String destinationCity, LocalDate startDate, Integer travelDays,
        Integer travelerCount, BigDecimal budget, List<String> preferences, String travelerType,
        List<String> mustVisitNames, String remark) {
    public TripInput normalized() {
        return new TripInput(clean(startCity), clean(destinationCity), startDate, travelDays, travelerCount,
                budget == null ? null : budget.stripTrailingZeros(), preferences == null ? List.of() : preferences.stream().map(TripInput::clean).distinct().sorted().toList(),
                clean(travelerType), mustVisitNames == null ? List.of() : mustVisitNames.stream().map(TripInput::clean).distinct().sorted().toList(), clean(remark));
    }
    public void validate() {
        if (startCity.isBlank() || startCity.length() > 100 || destinationCity.isBlank() || destinationCity.length() > 100) throw ApiError.bad("请填写有效的出发城市与目的城市");
        if (startDate == null || startDate.isBefore(LocalDate.now(ZoneId.of("Asia/Shanghai")))) throw ApiError.bad("出发日期不能早于今天");
        if (travelDays == null || travelDays < 1 || travelDays > 7 || travelerCount == null || travelerCount < 1 || travelerCount > 10) throw ApiError.bad("出行天数限 1～7 天，人数限 1～10 人");
        if (budget == null || budget.signum() <= 0 || budget.scale() > 2 || budget.compareTo(new BigDecimal("99999999.99")) > 0) throw ApiError.bad("预算须为最多两位小数的正数");
        if (preferences.isEmpty() || !Set.of("自然风光", "人文古迹", "美食探店", "城市休闲").containsAll(preferences)) throw ApiError.bad("请选择旅游偏好");
        if (!Set.of("单人", "情侣", "家庭", "朋友").contains(travelerType) || (travelerType.equals("单人") && travelerCount != 1) || (travelerType.equals("情侣") && travelerCount != 2) || ((travelerType.equals("家庭") || travelerType.equals("朋友")) && travelerCount < 2)) throw ApiError.bad("出行人群与人数不匹配");
        if (mustVisitNames.size() > 10 || mustVisitNames.stream().anyMatch(n -> n.isBlank() || n.length() > 100) || remark.length() > 500) throw ApiError.bad("必去景点或备注超过长度限制");
    }
    private static String clean(String s) { return s == null ? "" : s.strip(); }
}
