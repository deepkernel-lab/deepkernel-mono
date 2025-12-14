from flask import Flask, jsonify

app = Flask(__name__)

products = [
    {"id": 1, "name": "Laptop", "price": 1200},
    {"id": 2, "name": "Mouse", "price": 25},
    {"id": 3, "name": "Keyboard", "price": 75},
]

orders = [
    {"id": 1, "product_id": 1, "quantity": 1, "total": 1200},
    {"id": 2, "product_id": 2, "quantity": 2, "total": 50},
]

@app.route('/health', methods=['GET'])
def health():
    return jsonify({"status": "ok"})

@app.route('/products', methods=['GET'])
def get_products():
    return jsonify(products)

@app.route('/orders', methods=['GET'])
def get_orders():
    return jsonify(orders)

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=8002)
