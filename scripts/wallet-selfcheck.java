// 钱包自检小工具（随部署包提供）——从 24 词助记词推导「治理钱包」的三样信息：
//   ① 地址（对应网络形态）  ② 公钥（base64）  ③ walletId
// 并可与你已填/钱包 App 显示的地址自动对拍；不一致时自动扫描 subwallet 帮你找出正确 walletId。
//
// 用法（在应用部署目录，先填好 env.sh 里的 TGG_CHAIN_UPGRADE_WALLET_MNEMONIC）：
//   # ① 解开应用包（只需第一次；从 tgg-app.jar 里取出运行库）
//   unzip -q -o tgg-app.jar 'BOOT-INF/*' -d /tmp/tgg-cls
//   # ② 导入环境变量并运行（java 21）
//   set -a; . ./env.sh; set +a
//   java -cp "/tmp/tgg-cls/BOOT-INF/classes:/tmp/tgg-cls/BOOT-INF/lib/*" scripts/wallet-selfcheck.java
//   # ③ 想对拍 App 里显示的地址：加一行环境变量再跑
//   TGG_EXPECTED_WALLET_ADDRESS="<App 里的地址>" java -cp "..." scripts/wallet-selfcheck.java
//
// 读取的环境变量：
//   TGG_CHAIN_UPGRADE_WALLET_MNEMONIC   必填（24 词）
//   TGG_CHAIN_TESTNET                   可选（true 默认 / false=主网；决定默认 walletId 与地址形态）
//   TGG_CHAIN_UPGRADE_WALLET_ID         可选（缺省按网络公式算：testnet=2147483645 / mainnet=2147483409）
//   TGG_EXPECTED_WALLET_ADDRESS         可选（钱包 App 里显示的地址；给了就对拍）
//   TGG_FEDERATION_ADDRESS              可选（同上，没有 EXPECTED 时用它当对拍基准）
import com.iwebpp.crypto.TweetNaclFast;
import com.tg.escrow.chain.ChainWalletKeys;
import org.ton.ton4j.address.Address;
import java.util.Base64;

public class WalletSelfCheck {
    public static void main(String[] args) throws Exception {
        String mnemonic = System.getenv("TGG_CHAIN_UPGRADE_WALLET_MNEMONIC");
        if (mnemonic == null || mnemonic.isBlank()) {
            System.out.println("ABORT：未设置 TGG_CHAIN_UPGRADE_WALLET_MNEMONIC（先 set -a; . ./env.sh; set +a）");
            System.exit(2);
        }
        boolean testnet = Boolean.parseBoolean(System.getenv().getOrDefault("TGG_CHAIN_TESTNET", "true"));
        long defaultId = ChainWalletKeys.v5r1WalletId(
                testnet ? ChainWalletKeys.TESTNET_GLOBAL_ID : ChainWalletKeys.MAINNET_GLOBAL_ID, 0, 0);
        long walletId = Long.parseLong(System.getenv().getOrDefault(
                "TGG_CHAIN_UPGRADE_WALLET_ID", String.valueOf(defaultId)));

        byte[] seed = ChainWalletKeys.seedFromMnemonic(mnemonic);
        Address addr = ChainWalletKeys.v5r1WalletAddress(seed, walletId);
        String pubB64 = Base64.getEncoder().encodeToString(
                TweetNaclFast.Signature.keyPair_fromSeed(seed).getPublicKey());

        System.out.println("网络:            " + (testnet ? "testnet（测试网）" : "mainnet（主网）"));
        System.out.println("地址(" + (testnet ? "kQ… 测试网形态" : "EQ… 主网形态") + "): "
                + (testnet ? addr.toBounceableTestnet() : addr.toBounceable()));
        System.out.println("地址(raw):       " + addr.toRaw());
        System.out.println("walletId:        " + walletId + (walletId == defaultId ? "（网络默认值）" : "（来自 env 覆盖）"));
        System.out.println("公钥(base64):    " + pubB64);
        System.out.println();

        String expected = System.getenv("TGG_EXPECTED_WALLET_ADDRESS");
        if (expected == null || expected.isBlank()) expected = System.getenv("TGG_FEDERATION_ADDRESS");
        if (expected == null || expected.isBlank()) {
            System.out.println("提示：想与钱包 App 显示的地址对拍，加环境变量 TGG_EXPECTED_WALLET_ADDRESS 再跑一次。");
            System.exit(0);
        }
        String expectedRaw = null;
        try {
            expectedRaw = Address.of(expected.trim()).toRaw();
        } catch (Exception e) {
            System.out.println("⚠️ 无法解析 TGG_EXPECTED_WALLET_ADDRESS（\"" + expected + "\"）——检查地址是否复制完整。");
        }
        if (expectedRaw == null) {
            System.exit(3);
        }
        if (expectedRaw.equals(addr.toRaw())) {
            System.out.println("✅ 对拍一致：助记词、walletId、地址三者配套——可放心填进 env.sh。");
            System.exit(0);
        }
        System.out.println("⚠️ 对拍不一致！当前 walletId=" + walletId + " 推导的地址与你给的地址不同。");
        System.out.println("   正在扫描 subwallet（0…1023）帮你找正确 walletId…");
        for (int s = 0; s <= 1023; s++) {
            long wid = ChainWalletKeys.v5r1WalletId(
                    testnet ? ChainWalletKeys.TESTNET_GLOBAL_ID : ChainWalletKeys.MAINNET_GLOBAL_ID, 0, s);
            if (ChainWalletKeys.v5r1WalletAddress(seed, wid).toRaw().equals(expectedRaw)) {
                System.out.println("   ✔ 找到匹配：subwallet=" + s + " → TGG_CHAIN_UPGRADE_WALLET_ID=" + wid);
                System.out.println("   把上面的 walletId 填进 env.sh 后重跑本工具复核。");
                System.exit(0);
            }
        }
        System.out.println("   ✘ 扫描无果：你的地址不是从这 24 词、也非 v5r1(subwallet 0-1023) 推导而来。");
        System.out.println("     请核对：① 助记词是否抄错/顺序错 ② 钱包 App 是否为 V5(R1) 钱包（老 V3/V4 钱包不兼容）。");
        System.exit(4);
    }
}
