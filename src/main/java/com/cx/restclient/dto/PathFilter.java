package com.cx.restclient.dto;

import com.cx.restclient.common.ShragaUtils;

import java.util.List;
import java.util.Map;

public class PathFilter {

    private String[] includes;
    private String[] excludes;

    public PathFilter(String folderExclusions, String filterPattern) {
        Map<String, List<String>> stringListMap = ShragaUtils.generateIncludesExcludesPatternLists(folderExclusions, filterPattern);
        includes = getArray(stringListMap, ShragaUtils.INCLUDES_LIST);
        excludes = getArray(stringListMap, ShragaUtils.EXCLUDES_LIST);
    }

    public String[] getIncludes() {
        return includes;
    }

    public String[] getExcludes() {
        return excludes;
    }

    private static String[] getArray(Map<String, List<String>> map, String key) {
        return map.get(key).toArray(new String[0]);
    }

}
