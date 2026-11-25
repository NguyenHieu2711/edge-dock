package com.nguyenhieu.edgedock;

interface IShizukuShellService {
    String execCommand(String command);
    String getTopTaskInfo(String ignorePackagesCsv);
    int findTaskIdForPackage(String packageName);
    boolean moveTaskToFront(int taskId);
    boolean setTaskExcludedFromRecents(int taskId, boolean excluded);
    void destroy();
}
