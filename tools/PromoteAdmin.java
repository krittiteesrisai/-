import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;

/**
 * ตั้งบัญชีที่สมัครไว้แล้วให้เป็น ADMIN บน DB ของ fork (Render)
 *
 * ใช้: java -cp <postgresql.jar> tools/PromoteAdmin.java "<External Database URL จาก Render>" <username>
 */
public class PromoteAdmin {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: java -cp postgresql.jar tools/PromoteAdmin.java \"postgresql://user:pass@host:port/db\" username");
            System.exit(2);
        }
        URI uri = URI.create(args[0].replaceFirst("^postgres(ql)?://", "http://"));
        String[] credentials = uri.getRawUserInfo().split(":", 2);
        String user = URLDecoder.decode(credentials[0], StandardCharsets.UTF_8);
        String password = URLDecoder.decode(credentials[1], StandardCharsets.UTF_8);
        int port = uri.getPort() == -1 ? 5432 : uri.getPort();
        String url = "jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getPath() + "?sslmode=require";

        try (Connection connection = DriverManager.getConnection(url, user, password);
             PreparedStatement update = connection.prepareStatement("UPDATE users SET role = 'ADMIN' WHERE username = ?")) {
            update.setString(1, args[1]);
            int rows = update.executeUpdate();
            System.out.println(rows == 1
                    ? "OK: " + args[1] + " เป็น ADMIN แล้ว ให้ logout แล้ว login ใหม่"
                    : "ไม่เจอผู้ใช้ชื่อ " + args[1] + " (สมัครที่ /register ก่อน)");
        }
    }
}
