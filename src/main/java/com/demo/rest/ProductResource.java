package com.demo.rest;

import jakarta.annotation.Resource;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Product REST API (Liberty + Db2)
 *
 * 動的に SQL を組み立て、渡すバインド変数は必要な分だけ
 * LIKE 用パターンは Java 側で生成 ("%WORD%")
 */
@Path("/api")
public class ProductResource {

    /** Db2 用 JNDI 名 */
    @Resource(lookup = "jdbc/Db2DS")
    private DataSource ds;

    /* --------------------------------------------------
       /products  : 全商品取得
       -------------------------------------------------- */
    @GET
    @Path("/products")
    @Produces(MediaType.APPLICATION_JSON)
    public List<Product> getProducts() throws SQLException {
        String sql =
                "SELECT p.id, p.name, p.description, p.category_id, p.brand_id, " +
                "       c.name AS category_name, b.name AS brand_name " +
                "FROM   products p " +
                "LEFT  JOIN categories c ON p.category_id = c.id " +
                "LEFT  JOIN brands     b ON p.brand_id    = b.id";

        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return mapProducts(rs);
        }
    }

    /* --------------------------------------------------
       /search  : 商品検索（ページング）
       -------------------------------------------------- */
    @GET
    @Path("/search")
    @Produces(MediaType.APPLICATION_JSON)
    public List<Product> searchProducts(
            @QueryParam("productName")  String productName,
            @QueryParam("categoryName") String categoryName,
            @QueryParam("brandName")    String brandName,
            @QueryParam("page") @DefaultValue("1") int page) throws SQLException {

        final int pageSize = 100;
        final int offset   = Math.max(page - 1, 0) * pageSize;

        // --- SQL を動的生成 --------------------------------------------------
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT p.id, p.name, p.description, ")
           .append("       c.name AS category_name, ")
           .append("       b.name AS brand_name ")
           .append("FROM   products p ")
           .append("LEFT JOIN categories c ON c.id = p.category_id ")
           .append("LEFT JOIN brands     b ON b.id = p.brand_id ");
        
        List<Object> params = new ArrayList<>();
        
        boolean first = true;
        
        if (!isNullOrEmpty(productName)) {
            sql.append(first ? " WHERE " : " AND ");
            sql.append("UPPER(p.name) LIKE ?");
            params.add(toLikePattern(productName));
            first = false;
        }
        if (!isNullOrEmpty(categoryName)) {
            sql.append(first ? " WHERE " : " AND ");
            sql.append("UPPER(c.name) LIKE ?");
            params.add(toLikePattern(categoryName));
            first = false;
        }
        if (!isNullOrEmpty(brandName)) {
            sql.append(first ? " WHERE " : " AND ");
            sql.append("UPPER(b.name) LIKE ?");
            params.add(toLikePattern(brandName));
            first = false;
        }
        
        sql.append(" ORDER BY p.id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
        params.add(offset);
        params.add(pageSize);
        
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = prepare(conn, sql.toString(), params);
             ResultSet rs = ps.executeQuery()) {
            return mapProducts(rs);
        }

    }

    /* --------------------------------------------------
       /search/count : ヒット件数
       -------------------------------------------------- */
    @GET
    @Path("/search/count")
    @Produces(MediaType.APPLICATION_JSON)
    public int searchProductsCount(
            @QueryParam("productName")  String productName,
            @QueryParam("categoryName") String categoryName,
            @QueryParam("brandName")    String brandName) throws SQLException {

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COUNT(*) AS total ")
           .append("FROM   products p ")
           .append("LEFT JOIN categories c ON c.id = p.category_id ")
           .append("LEFT JOIN brands     b ON b.id = p.brand_id ")
           .append("WHERE 1=1");

        List<Object> params = new ArrayList<>();

        if (!isNullOrEmpty(productName)) {
            sql.append(" AND UPPER(p.name) LIKE ?");
            params.add(toLikePattern(productName));
        }
        if (!isNullOrEmpty(categoryName)) {
            sql.append(" AND UPPER(c.name) LIKE ?");
            params.add(toLikePattern(categoryName));
        }
        if (!isNullOrEmpty(brandName)) {
            sql.append(" AND UPPER(b.name) LIKE ?");
            params.add(toLikePattern(brandName));
        }

        try (Connection conn = ds.getConnection();
             PreparedStatement ps = prepare(conn, sql.toString(), params);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt("total") : 0;
        }
    }

    /* --------------------------------------------------
       /categories : カテゴリ一覧
       -------------------------------------------------- */
    @GET
    @Path("/categories")
    @Produces(MediaType.APPLICATION_JSON)
    public List<Category> getCategories() throws SQLException {
        String sql = "SELECT id, name FROM categories ORDER BY id";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<Category> list = new ArrayList<>();
            while (rs.next()) {
                Category c = new Category();
                c.setId(rs.getLong("id"));
                c.setName(rs.getString("name"));
                list.add(c);
            }
            return list;
        }
    }

    /* --------------------------------------------------
       /brands : ブランド一覧
       -------------------------------------------------- */
    @GET
    @Path("/brands")
    @Produces(MediaType.APPLICATION_JSON)
    public List<Brand> getBrands() throws SQLException {
        String sql = "SELECT id, name FROM brands ORDER BY id";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<Brand> list = new ArrayList<>();
            while (rs.next()) {
                Brand b = new Brand();
                b.setId(rs.getLong("id"));
                b.setName(rs.getString("name"));
                list.add(b);
            }
            return list;
        }
    }

    /* --------------------------------------------------
       共通ユーティリティ
       -------------------------------------------------- */
    private static boolean isNullOrEmpty(String s) {
        return s == null || s.isEmpty();
    }

    private static String toLikePattern(String keyword) {
        return "%" + keyword.toUpperCase() + "%";  // 呼び出し側で null/empty チェック済み
    }

    /** PreparedStatement にリストの値を順番にバインド */
    private static PreparedStatement prepare(Connection conn, String sql, List<Object> params) throws SQLException {
        PreparedStatement ps = conn.prepareStatement(sql);
        for (int i = 0; i < params.size(); i++) {
            Object val = params.get(i);
            if (val instanceof String) {
                ps.setString(i + 1, (String) val);
            } else if (val instanceof Integer) {
                ps.setInt(i + 1, (int) val);
            } else if (val instanceof Long) {
                ps.setLong(i + 1, (long) val);
            } else {
                ps.setObject(i + 1, val);
            }
        }
        return ps;
    }

    /** ResultSet → List<Product> */
    private static List<Product> mapProducts(ResultSet rs) throws SQLException {
        List<Product> list = new ArrayList<>();
        while (rs.next()) {
            Product p = new Product();
            p.setId(rs.getLong("id"));
            p.setName(rs.getString("name"));
            p.setDescription(rs.getString("description"));
            p.setCategoryName(rs.getString("category_name"));
            p.setBrandName(rs.getString("brand_name"));
            list.add(p);
        }
        return list;
    }
}
