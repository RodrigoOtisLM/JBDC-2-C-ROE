package lsi.ubu.servicios;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Date;
import java.util.logging.Logger;

import lsi.ubu.excepciones.AlquilerCochesException;
import lsi.ubu.util.PoolDeConexiones;
import lsi.ubu.Misc;

public class ServicioImpl implements Servicio {

	private static Logger logger = Logger.getLogger(ServicioImpl.class.getName());
	private static PoolDeConexiones pool = PoolDeConexiones.getInstance();

	@Override
	public void alquilar(String nifCliente, String matricula, Date fechaIni, Date fechaFin) throws SQLException {

		Connection con = null;
		PreparedStatement ps = null;
		ResultSet rs = null;

		try {
			con = pool.getConnection();
			con.setAutoCommit(false); // iniciar transaccion

			// 1. Validar que el cliente existe
			String sqlCheckCliente = "SELECT COUNT(*) FROM clientes WHERE NIF = ?";
			ps = con.prepareStatement(sqlCheckCliente);
			ps.setString(1, nifCliente);
			rs = ps.executeQuery();
			rs.next();
			if (rs.getInt(1) == 0) {
				throw new AlquilerCochesException(AlquilerCochesException.CLIENTE_NO_EXIST);
			}
			rs.close();
			ps.close();

			// 2. Validar que el vehículo existe
			String sqlCheckVehiculo = "SELECT COUNT(*) FROM vehiculos WHERE matricula = ?";
			ps = con.prepareStatement(sqlCheckVehiculo);
			ps.setString(1, matricula);
			rs = ps.executeQuery();
			rs.next();
			if (rs.getInt(1) == 0) {
				throw new AlquilerCochesException(AlquilerCochesException.VEHICULO_NO_EXIST);
			}
			rs.close();
			ps.close();

			//3. Validar que el número de días es mayor que cero
			Date fechaFinReal = fechaFin;
			int numDias;

			if (fechaFin == null) {
				// Si no se especifica fecha fin, calcular 4 días para el importepero guardamos NULL en la bbdd.
				numDias = 4;
				fechaFinReal = null; //null para la base de datos
			} else {
				numDias = Misc.howManyDaysBetween(fechaFin, fechaIni);
				fechaFinReal = fechaFin;
			}

			if (numDias < 1) {
				throw new AlquilerCochesException(AlquilerCochesException.SIN_DIAS);
			}

			// 4. Verificar que el vehiculo está disponible en esas fechas. Solo verificar si tenemos fechaFinb ien definida
			if (fechaFin != null) {
				String sqlCheckDisponibilidad = "SELECT COUNT(*) FROM reservas WHERE matricula = ? AND "
						+ "(fecha_ini <= ? AND fecha_fin >= ?) OR " + "(fecha_ini <= ? AND fecha_fin >= ?) OR "
						+ "(fecha_ini >= ? AND fecha_fin <= ?)";
				ps = con.prepareStatement(sqlCheckDisponibilidad);
				ps.setString(1, matricula);
				ps.setDate(2, new java.sql.Date(fechaFin.getTime()));
				ps.setDate(3, new java.sql.Date(fechaFin.getTime()));
				ps.setDate(4, new java.sql.Date(fechaIni.getTime()));
				ps.setDate(5, new java.sql.Date(fechaIni.getTime()));
				ps.setDate(6, new java.sql.Date(fechaIni.getTime()));
				ps.setDate(7, new java.sql.Date(fechaFin.getTime()));
				rs = ps.executeQuery();
				rs.next();
				if (rs.getInt(1) > 0) {
					throw new AlquilerCochesException(AlquilerCochesException.VEHICULO_OCUPADO);
				}
				rs.close();
				ps.close();
			}

			// 5. Crear reserva
			String sqlInsertReserva = "INSERT INTO reservas (idReserva, cliente, matricula, fecha_ini, fecha_fin) "
					+ "VALUES (seq_reservas.nextval, ?, ?, ?, ?)";
			ps = con.prepareStatement(sqlInsertReserva);
			ps.setString(1, nifCliente);
			ps.setString(2, matricula);
			ps.setDate(3, new java.sql.Date(fechaIni.getTime()));
			if (fechaFinReal != null) {
				ps.setDate(4, new java.sql.Date(fechaFinReal.getTime()));
			} else {
				ps.setNull(4, java.sql.Types.DATE);
			}
			ps.executeUpdate();
			ps.close();

			// 6. Obtener datos para calcular importe de la factura
			String sqlGetModelo = "SELECT m.precio_cada_dia, m.capacidad_deposito, pc.precio_por_litro "
					+ "FROM modelos m, vehiculos v, precio_combustible pc "
					+ "WHERE v.matricula = ? AND v.id_modelo = m.id_modelo "
					+ "AND m.tipo_combustible = pc.tipo_combustible";
			ps = con.prepareStatement(sqlGetModelo);
			ps.setString(1, matricula);
			rs = ps.executeQuery();

			if (rs.next()) {
				double precioPorDia = rs.getDouble("precio_cada_dia");
				int capacidadDeposito = rs.getInt("capacidad_deposito");
				double precioCombustible = rs.getDouble("precio_por_litro");

				//calcular importes
				double importeAlquiler = precioPorDia * numDias;
				double importeCombustible = capacidadDeposito * precioCombustible;
				double importeTotal = importeAlquiler + importeCombustible;

				rs.close();
				ps.close();

				// 7. Crear factura
				String sqlInsertFactura = "INSERT INTO facturas (nroFactura, importe, cliente) "
						+ "VALUES (seq_num_fact.nextval, ?, ?)";
				ps = con.prepareStatement(sqlInsertFactura);
				ps.setDouble(1, importeTotal);
				ps.setString(2, nifCliente);
				ps.executeUpdate();
				ps.close();

				// 8. Obtener el nº de factura generado
				String sqlGetFacturaId = "SELECT seq_num_fact.currval FROM dual";
				ps = con.prepareStatement(sqlGetFacturaId);
				rs = ps.executeQuery();
				rs.next();
				int nroFactura = rs.getInt(1);
				rs.close();
				ps.close();

				// 9. Crear lineas de factura
				String sqlInsertLinea = "INSERT INTO lineas_factura (nroFactura, concepto, importe) VALUES (?, ?, ?)";

				//obtener info del modelo para el concepto
				String sqlGetModeloInfo = "SELECT m.id_modelo, m.tipo_combustible, m.capacidad_deposito "
						+ "FROM modelos m, vehiculos v " + "WHERE v.matricula = ? AND v.id_modelo = m.id_modelo";
				PreparedStatement psModelo = con.prepareStatement(sqlGetModeloInfo);
				psModelo.setString(1, matricula);
				ResultSet rsModelo = psModelo.executeQuery();

				if (rsModelo.next()) {
					int idModelo = rsModelo.getInt("id_modelo");
					String tipoCombustible = rsModelo.getString("tipo_combustible");
					int capacidad = rsModelo.getInt("capacidad_deposito");

					// Línea de alquiler - Formato corto sin importe
					ps = con.prepareStatement(sqlInsertLinea);
					ps.setInt(1, nroFactura);
					ps.setString(2, numDias + " dias de alquiler, vehiculo modelo " + idModelo);
					ps.setDouble(3, importeAlquiler);
					ps.executeUpdate();
					ps.close();

					// Línea de combustible con formato corto sin importe
					ps = con.prepareStatement(sqlInsertLinea);
					ps.setInt(1, nroFactura);
					ps.setString(2, "Deposito lleno de " + capacidad + " litros de " + tipoCombustible);
					ps.setDouble(3, importeCombustible);
					ps.executeUpdate();
					ps.close();
				}
				rsModelo.close();
				psModelo.close();
			}

			//confirmamos transaccion
			con.commit();

		} catch (AlquilerCochesException e) {
			// Rollback para excepciones de el negocio
			if (con != null) {
				try {
					con.rollback();
				} catch (SQLException rollbackEx) {
					logger.severe("Error en rollback: " + rollbackEx.getMessage());
				}
			}
			// NOOOOOO 42000 pq es un estado generico para errores de aplicacion, cuando
			// cuando habia null causaba problemas
			throw new SQLException("Error de negocio: " + e.getMessage(), "42000", e.getErrorCode());

		} catch (Exception e) {
			// Rollback para las otras excepciones
			if (con != null) {
				try {
					con.rollback();
				} catch (SQLException rollbackEx) {
					logger.severe("Error en rollback: " + rollbackEx.getMessage());
				}
			}
			logger.severe("Error en alquilar: " + e.getMessage());
			throw new SQLException("Error al crear alquiler: " + e.getMessage(), e);

		} finally {
			//crramos recursos
			if (rs != null) {
				try {
					rs.close();
				} catch (SQLException e) {
					logger.warning("Error cerrando ResultSet: " + e.getMessage());
				}
			}
			if (ps != null) {
				try {
					ps.close();
				} catch (SQLException e) {
					logger.warning("Error cerrando PreparedStatement: " + e.getMessage());
				}
			}
			if (con != null) {
				try {
					con.setAutoCommit(true); // Restaurar autocommit
					con.close();
				} catch (SQLException e) {
					logger.warning("Error devolviendo conexión: " + e.getMessage());
				}
			}
		}
	}

	@Override
	public void anular_alquiler(String idReserva, String nifCliente, String matricula, Date fechaIni, Date fechaFin)
			throws SQLException {

		Connection con = null;
		PreparedStatement ps = null;
		ResultSet rs = null;

		try {
			con = pool.getConnection(); //comenzamos transaccion
			con.setAutoCommit(false); 

			// 1. Validar que la reserva existe
			String sqlCheckReserva = "SELECT COUNT(*) FROM reservas WHERE idReserva = ?";
			ps = con.prepareStatement(sqlCheckReserva);
			ps.setInt(1, Integer.parseInt(idReserva));
			rs = ps.executeQuery();
			rs.next();
			if (rs.getInt(1) == 0) {
				throw new AlquilerCochesException(AlquilerCochesException.RESERVA_NO_EXIST);
			}
			rs.close();
			ps.close();

			// 2. Validar que cliente existe
			String sqlCheckCliente = "SELECT COUNT(*) FROM clientes WHERE NIF = ?";
			ps = con.prepareStatement(sqlCheckCliente);
			ps.setString(1, nifCliente);
			rs = ps.executeQuery();
			rs.next();
			if (rs.getInt(1) == 0) {
				throw new AlquilerCochesException(AlquilerCochesException.CLIENTE_NO_EXIST);
			}
			rs.close();
			ps.close();

			// 3. validar que el vehiculo existe
			String sqlCheckVehiculo = "SELECT COUNT(*) FROM vehiculos WHERE matricula = ?";
			ps = con.prepareStatement(sqlCheckVehiculo);
			ps.setString(1, matricula);
			rs = ps.executeQuery();
			rs.next();
			if (rs.getInt(1) == 0) {
				throw new AlquilerCochesException(AlquilerCochesException.VEHICULO_NO_EXIST);
			}
			rs.close();
			ps.close();

			// 4 Validar que el numero de dias es > 0.
			int numDias = Misc.howManyDaysBetween(fechaFin, fechaIni);
			if (numDias < 1) {
				throw new AlquilerCochesException(AlquilerCochesException.SIN_DIAS);
			}

			// 5. Verificamos que la reserva corresponde a los datos propocionados y que vehículo está disponible (reserva existe y coincide)
			String sqlCheckReservaCompleta = "SELECT COUNT(*) FROM reservas WHERE idReserva = ? AND cliente = ? AND matricula = ? "
					+ "AND fecha_ini = ? AND fecha_fin = ?";
			ps = con.prepareStatement(sqlCheckReservaCompleta);
			ps.setInt(1, Integer.parseInt(idReserva));
			ps.setString(2, nifCliente);
			ps.setString(3, matricula);
			ps.setDate(4, new java.sql.Date(fechaIni.getTime()));
			ps.setDate(5, new java.sql.Date(fechaFin.getTime()));
			rs = ps.executeQuery();
			rs.next();
			if (rs.getInt(1) == 0) {
				throw new AlquilerCochesException(AlquilerCochesException.VEHICULO_OCUPADO);
			}
			rs.close();
			ps.close();

			// 6. Buscar facturas asociadas a esta reserva
				//necesitamos obtener el número de factura del cliente para estas fechas
			String sqlGetFactura = "SELECT f.nroFactura FROM facturas f " + "WHERE f.cliente = ? AND EXISTS ("
					+ "    SELECT 1 FROM reservas r " + "    WHERE r.cliente = f.cliente AND r.idReserva = ?" + ")";
			ps = con.prepareStatement(sqlGetFactura);
			ps.setString(1, nifCliente);
			ps.setInt(2, Integer.parseInt(idReserva));
			rs = ps.executeQuery();

			//7. Eliminar lineas de factura asociadas
			while (rs.next()) {
				int nroFactura = rs.getInt("nroFactura");

				// Eliminar lineas factura
				String sqlDeleteLineas = "DELETE FROM lineas_factura WHERE nroFactura = ?";
				PreparedStatement psLineas = con.prepareStatement(sqlDeleteLineas);
				psLineas.setInt(1, nroFactura);
				psLineas.executeUpdate();
				psLineas.close();

				//Eliminar factura
				String sqlDeleteFactura = "DELETE FROM facturas WHERE nroFactura = ?";
				PreparedStatement psFactura = con.prepareStatement(sqlDeleteFactura);
				psFactura.setInt(1, nroFactura);
				psFactura.executeUpdate();
				psFactura.close();
			}
			rs.close();
			ps.close();

			// 8. Eliminamos la reserva
			String sqlDeleteReserva = "DELETE FROM reservas WHERE idReserva = ?";
			ps = con.prepareStatement(sqlDeleteReserva);
			ps.setInt(1, Integer.parseInt(idReserva));
			ps.executeUpdate();
			ps.close();

			// Confirmar transaccin
			con.commit();

		} catch (AlquilerCochesException e) {
			// Rollback para excepciones del negocio
			if (con != null) {
				try {
					con.rollback();
				} catch (SQLException rollbackEx) {
					logger.severe("Error en rollback: " + rollbackEx.getMessage());
				}
			}
			//MIRARRRRRRRRR
			throw new SQLException("Error de negocio: " + e.getMessage(), "42000", e.getErrorCode());

		} catch (Exception e) {
			// Rollback para demas excepciones
			if (con != null) {
				try {
					con.rollback();
				} catch (SQLException rollbackEx) {
					logger.severe("Error en rollback: " + rollbackEx.getMessage());
				}
			}
			logger.severe("Error en anular_alquiler: " + e.getMessage());
			throw new SQLException("Error al anular alquiler: " + e.getMessage(), e);
		} finally {
			// Cerrar recursos
			if (rs != null) {
				try {
					rs.close();
				} catch (SQLException e) {
					logger.warning("Error cerrando ResultSet: " + e.getMessage());
				}
			}
			if (ps != null) {
				try {
					ps.close();
				} catch (SQLException e) {
					logger.warning("Error cerrando PreparedStatement: " + e.getMessage());
				}
			}
			if (con != null) {
				try {
					con.setAutoCommit(true); // Restaurar  el autocommit
					con.close();
				} catch (SQLException e) {
					logger.warning("Error devolviendo conexión: " + e.getMessage());
				}
			}
		}
	}
}