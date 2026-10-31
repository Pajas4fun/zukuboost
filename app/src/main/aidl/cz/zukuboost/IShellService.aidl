package cz.zukuboost;

interface IShellService {
    // Kód 16777114 je rezervovaný Shizuku serverem pro ukončení user service
    void destroy() = 16777114;

    String exec(String cmd) = 1;
}
